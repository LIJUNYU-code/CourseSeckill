package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.common.ResultCode;
import com.example.seckill.dto.SeckillResponse;
import com.example.seckill.entity.Course;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.infra.cache.StockCacheService;
import com.example.seckill.mapper.CourseMapper;
import com.example.seckill.mapper.CourseStockMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.service.SeckillService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Service
@AllArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "seckill.strategy", havingValue = "v2-redis-lua")
public class SeckillServiceImplV2RedisLua implements SeckillService {

    private final CourseMapper courseMapper;
    private final CourseStockMapper courseStockMapper;
    private final SeckillOrderMapper orderMapper;
    private final StockCacheService stockCacheService;
    private final TransactionTemplate transactionTemplate;


    private String generateOrderNo(Long studentId) {
        return System.currentTimeMillis() + String.format("%04d", studentId % 10000)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }


    @Override
    @Transactional
    public SeckillResponse seckill(Long courseId) {
        // ---------- 第 1 步：查课程（与 v1 完全一样）----------
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(course.getStartTime()) || now.isAfter(course.getEndTime())) {
            throw new BusinessException(ResultCode.CONFLICT, "课程尚未开放选课");
        }
        Long studentId = (Long) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        // ---------- 第 2 步：Lua 扣 Redis ----------
        Long left = stockCacheService.tryDecrement(courseId);
        if (left == -1L) {
            // 未预热：现场预热后只重试一次（不循环！Redis 挂了会死循环打爆自己）
            log.warn("Redis 未预热，现场预热 courseId={}", courseId);
            stockCacheService.preheat(courseId);
            left = stockCacheService.tryDecrement(courseId);
        }
        if (left == -2L) {
            // ★ 200 个失败请求在这里就返回了，一次 MySQL 都没碰
            throw new BusinessException(ResultCode.CONFLICT, "该课程名额已满");
        }
        if (left < 0) {
            // -1 重试后还是 -1，说明数据库里没这行库存，属于数据问题
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "库存状态异常");
        }

        // ---------- 第 3~5 步：MySQL + 查重 + INSERT ----------
        // 从这里开始，任何失败都必须把 Redis 上刚扣的那个名额还回去
        boolean success = false;
        try {
            int affected = courseStockMapper.tryDecrement(courseId);
            if (affected == 0) {
                // Redis 说还有，MySQL 说没了 —— 两边不一致，但以 MySQL 为准
                log.warn("MySQL 扣减失败，Redis={} courseId={} 需对账", left, courseId);
                throw new BusinessException(ResultCode.CONFLICT, "该课程名额已满");
            }

            SeckillOrder exist = orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
                    .eq(SeckillOrder::getStudentId, studentId)
                    .eq(SeckillOrder::getCourseId, courseId));
            if (exist != null) {
                if (exist.getStatus() == SeckillOrder.STATUS_SUCCESS
                        || exist.getStatus() == SeckillOrder.STATUS_PENDING) {
                    throw new BusinessException(ResultCode.CONFLICT, "你已选过该课程");
                }
                if (exist.getStatus() == SeckillOrder.STATUS_CANCELLED) {
                    // R5：退课后重选，改回成功，不 INSERT
                    exist.setStatus(SeckillOrder.STATUS_SUCCESS);
                    exist.setFailReason(null);
                    orderMapper.updateById(exist);
                    success = true;               // ← 这一分支算成功，别忘了
                    return new SeckillResponse(exist.getOrderNo(), exist.getStatus());
                }
            }

            SeckillOrder order = SeckillOrder.builder()
                    .orderNo(generateOrderNo(studentId))
                    .studentId(studentId)
                    .courseId(courseId)
                    .status(SeckillOrder.STATUS_SUCCESS)
                    .build();
            orderMapper.insert(order);            // 撞 uk_student_course → 异常 → 回滚
            success = true;
            return new SeckillResponse(order.getOrderNo(), order.getStatus());

        } finally {
            if (!success) {
                try {
                    stockCacheService.increment(courseId);
                } catch (Exception e) {
                    // 吞掉但不静默：这条日志是对账的入口，压测后 grep 它
                    log.error("Redis 库存归还失败，需要对账 courseId={} studentId={}",
                            courseId, studentId, e);
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
            SeckillOrder order = orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
                    .eq(SeckillOrder::getStudentId, studentId)
                    .eq(SeckillOrder::getCourseId, courseId));
            if (order == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "未找到该课程的选课记录");
            }

            int affected = orderMapper.updateStatusIfMatch(
                    order.getId(), SeckillOrder.STATUS_CANCELLED, SeckillOrder.STATUS_SUCCESS
            );

            if (affected == 0) {
                throw new BusinessException(ResultCode.CONFLICT, "该课程当前状态无法退课");
            }

            courseStockMapper.returnStock(courseId);

            log.info("退课成功 orderNo={} courseId={} studentId={}", order.getOrderNo(),courseId, studentId );
            return order;
        });
        // ===== 第二段：事务已提交，MySQL 是准的，现在才还 Redis ===
        try{
            stockCacheService.incrementIfPresent(courseId);
        }catch (Exception e){
            //不抛！MySQL 已经提交，抛出去会让前端以为退课失败，
            // 用户再点一次只会得到 409，反而更困惑。
            // Redis 偏低是安全方向，交给对账/预热修。
            log.error("退课后 Redis 归还失败（Redis 偏低，不会超卖），需对账 courseId={} studentId={}",
                    courseId, studentId, e);
        }
        return new SeckillResponse(cancelled.getOrderNo(), SeckillOrder.STATUS_CANCELLED);
    }
}


