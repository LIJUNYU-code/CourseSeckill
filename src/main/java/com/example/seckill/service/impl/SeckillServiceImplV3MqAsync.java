package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.common.ResultCode;
import com.example.seckill.dto.SeckillResponse;
import com.example.seckill.entity.Course;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.infra.cache.StockCacheService;
import com.example.seckill.infra.mq.SeckillMessage;
import com.example.seckill.infra.mq.SeckillMessageProducer;
import com.example.seckill.mapper.CourseMapper;
import com.example.seckill.mapper.CourseStockMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.service.SeckillService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@AllArgsConstructor
@ConditionalOnProperty(name = "seckill.strategy", havingValue = "v3-mq-async")
public class SeckillServiceImplV3MqAsync implements SeckillService {

    private final CourseMapper courseMapper;
    private final CourseStockMapper courseStockMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final StockCacheService stockCacheService;
    private final SeckillMessageProducer messageProducer;
    private final TransactionTemplate transactionTemplate;


    private String generateOrderNo(Long studentId) {
        return System.currentTimeMillis() + String.format("%04d", studentId % 10000)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    @Override
    public SeckillResponse seckill(Long courseId) {

        //课程校验
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(course.getStartTime()) || now.isAfter(course.getEndTime())) {
            throw new BusinessException(ResultCode.CONFLICT, "课程尚未开放选课");
        }
        Long studentId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        //Redis 门禁
        Long left = stockCacheService.tryDecrement(courseId);
        if (left == -1L) {
            stockCacheService.preheat(courseId);
            left = stockCacheService.tryDecrement(courseId);
        }
        if (left == -2L) {
            throw new BusinessException(ResultCode.CONFLICT, "该课程名额已满");
        }
        if (left < 0) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "库存状态异常");
        }

        //后续所有步骤都可能失败，失败要还 Redis
        boolean success = false;
        String createdOrderNo = null;
        try {
            //查重
            SeckillOrder existing = seckillOrderMapper.selectOne(
                    new LambdaQueryWrapper<SeckillOrder>()
                            .eq(SeckillOrder::getStudentId, studentId)
                            .eq(SeckillOrder::getCourseId, courseId)
            );
            String orderNo;
            if (existing != null) {
                if (existing.getStatus() == SeckillOrder.STATUS_SUCCESS || existing.getStatus() == SeckillOrder.STATUS_PENDING) {
                    throw new BusinessException(ResultCode.CONFLICT, "你已选过该课程");
                }
                //CANCELLED 或 FAILED 复用旧帐单
                orderNo = existing.getOrderNo();
                int claimed = seckillOrderMapper.updateStatusIfMatch(
                        existing.getId(), SeckillOrder.STATUS_PENDING, existing.getStatus());
                if (claimed == 0) {
                    // 别人抢先把它改成 PENDING 了（并发双击 / 前端重试）。
                    // 不能继续：继续就会多发一条消息、多扣一次 Redis。
                    // 此时 success 仍是 false，finally 里的 incrementIfPresent 会把 Redis 补回去。
                    throw new BusinessException(ResultCode.CONFLICT, "操作过于频繁，请稍后重试");
                }
            } else {
                orderNo = generateOrderNo(studentId);
                SeckillOrder order = SeckillOrder.builder()
                        .orderNo(orderNo)
                        .studentId(studentId)
                        .courseId(courseId)
                        .status(SeckillOrder.STATUS_PENDING)
                        .build();
                seckillOrderMapper.insert(order);
            }
            createdOrderNo = orderNo;
            //发消息
            SeckillMessage message = new SeckillMessage(
                    UUID.randomUUID().toString(), orderNo, studentId, courseId);
            boolean sent = messageProducer.send(message);

            //nack 也要失败
            if (!sent) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "系统繁忙，请重试");
            }

            success = true;
            return new SeckillResponse(orderNo, SeckillOrder.STATUS_PENDING);
        } finally {
            if (!success) {
                //三储存回滚
                if (createdOrderNo != null) {
                    try {
                        seckillOrderMapper.delete(new LambdaQueryWrapper<SeckillOrder>()
                                .eq(SeckillOrder::getOrderNo, createdOrderNo));
                    } catch (Exception e) {
                        log.error("回滚订单失败 orderNo={}", createdOrderNo, e);
                        try {
                            seckillOrderMapper.updateStatusAndFailReason(
                                    createdOrderNo, SeckillOrder.STATUS_FAILED, "消息发送超时");

                        } catch (Exception e2) {
                            log.error("标记订单失败orderNo={}", createdOrderNo, e2);
                        }
                    }
                }
                try {
                    stockCacheService.incrementIfPresent(courseId);
                } catch (Exception e) {
                    log.error("Redis 库存归还失败，需要对账 courseId={}", courseId, e);
                }
            }
        }
    }

    @Override
    public SeckillResponse cancel(Long courseId) {
        Long studentId = (Long) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        // ===== 第一段：MySQL。整个 lambda 是一个事务，抛异常自动回滚 =====
        SeckillOrder cancelled = transactionTemplate.execute(status -> {
            SeckillOrder order = seckillOrderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
                    .eq(SeckillOrder::getStudentId, studentId)
                    .eq(SeckillOrder::getCourseId, courseId));
            if (order == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "未找到该课程的选课记录");
            }

            int affected = seckillOrderMapper.updateStatusIfMatch(
                    order.getId(), SeckillOrder.STATUS_CANCELLED, SeckillOrder.STATUS_SUCCESS
            );

            if (affected == 0) {
                throw new BusinessException(ResultCode.CONFLICT, "该课程当前状态无法退课");
            }

            courseStockMapper.returnStock(courseId);

            log.info("退课成功 orderNo={} courseId={} studentId={}", order.getOrderNo(), courseId, studentId);
            return order;
        });
        // ===== 第二段：事务已提交，MySQL 是准的，现在才还 Redis ===
        try {
            stockCacheService.incrementIfPresent(courseId);
        } catch (Exception e) {
            //不抛！MySQL 已经提交，抛出去会让前端以为退课失败，
            // 用户再点一次只会得到 409，反而更困惑。
            // Redis 偏低是安全方向，交给对账/预热修。
            log.error("退课后 Redis 归还失败（Redis 偏低，不会超卖），需对账 courseId={} studentId={}",
                    courseId, studentId, e);
        }
        return new SeckillResponse(cancelled.getOrderNo(), SeckillOrder.STATUS_CANCELLED);
    }   // 照搬 v2
}


