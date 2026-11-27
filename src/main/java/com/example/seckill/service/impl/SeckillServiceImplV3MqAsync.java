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
import org.springframework.dao.DuplicateKeyException;
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

    /** 订单号撞 {@code uk_order_no} 时最多换几次号。3 次够：连撞 3 次的概率是 1e-12 量级。 */
    private static final int ORDER_NO_MAX_ATTEMPTS = 3;

    private final CourseMapper courseMapper;
    private final CourseStockMapper courseStockMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final StockCacheService stockCacheService;
    private final SeckillMessageProducer messageProducer;
    private final TransactionTemplate transactionTemplate;


    /**
     * 生成订单号：13 位毫秒 + 4 位学号尾 + 4 位随机 = 21 位。
     *
     * <p>为什么带学号尾：同毫秒内不同学生的随机数空间仍然是 1/10000，
     * 但带上学号尾能让"同一学生同一毫秒重复提交"这类最常见的冲突分散到不同前缀上。
     *
     * <p>为什么不用 Snowflake：21 位够用，而 Snowflake 需要 workerId，
     * 多实例部署时 workerId 怎么分配本身就是新问题（写死会撞、动态分配要引入注册中心）。
     * 撞号概率极低，重试即可，不值得为此引入一套协调机制。
     */
    private String generateOrderNo(Long studentId) {
        return System.currentTimeMillis() + String.format("%04d", studentId % 10000)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    /**
     * 插入订单，如果撞的是 {@code uk_order_no} 就换个号重试。
     *
     * <p>为什么需要重试：订单号撞了会抛 {@code DuplicateKeyException}，
     * 全局异常处理器把它翻成 409 返回给前端，用户看到"订单号重复"完全无法理解，
     * 而且他这次抢课其实白扣了一个 Redis 名额。换个号重来对用户是无感的。
     *
     * <p>为什么要区分是哪个索引撞的：{@code seckill_order} 上有两个唯一索引——
     * <ul>
     *   <li>{@code uk_order_no}：订单号撞了，属于意外，换号重试；</li>
     *   <li>{@code uk_student_course}：这个学生已经选过这门课，属于正常业务分支，
     *       绝对不能重试（重试一万次也还是撞），应该原样抛出让上层处理。</li>
     * </ul>
     *
     * <p>⚠️ 靠解析异常消息里的索引名判断是脆弱的（MySQL 换版本可能改消息格式）。
     * 更稳的做法是先查后插，但那样又有竞态窗口。这里的取舍是：
     * 只在消息里出现 {@code uk_order_no} 时才重试，其它一律不重试——
     * 万一将来消息格式变了，最坏结果是"撞号时又变回 409"，而不是"重复选课被静默重试"。
     * 前者是体验问题，后者是正确性问题。
     */
    private String insertOrderWithRetry(Long studentId, Long courseId) {
        for (int attempt = 1; attempt <= ORDER_NO_MAX_ATTEMPTS; attempt++) {
            String orderNo = generateOrderNo(studentId);
            SeckillOrder order = SeckillOrder.builder()
                    .orderNo(orderNo)
                    .studentId(studentId)
                    .courseId(courseId)
                    .status(SeckillOrder.STATUS_PENDING)
                    .build();
            try {
                seckillOrderMapper.insert(order);
                return orderNo;
            } catch (DuplicateKeyException e) {
                if (!isOrderNoConflict(e)) {
                    // 撞的是 uk_student_course：这个学生真的已经选过这门课了。
                    // 走到这里说明前面的查重没查到（并发插入），交给上层转成 409。
                    throw new BusinessException(ResultCode.CONFLICT, "你已选过该课程");
                }
                log.warn("订单号撞号，换号重试 attempt={}/{} studentId={} courseId={}",
                        attempt, ORDER_NO_MAX_ATTEMPTS, studentId, courseId);
            }
        }
        // 连续撞满次数：随机数空间被踩穿了，这不是能靠重试解决的问题
        log.error("订单号连续 {} 次撞号，放弃 studentId={} courseId={}",
                ORDER_NO_MAX_ATTEMPTS, studentId, courseId);
        throw new BusinessException(ResultCode.INTERNAL_ERROR, "系统繁忙，请重试");
    }

    /**
     * 判断这个 {@code DuplicateKeyException} 是不是订单号唯一索引撞的。
     *
     * <p>MySQL 的原始消息形如
     * {@code Duplicate entry '179456451615900013418' for key 'seckill_order.uk_order_no'}，
     * 外层 Spring 包装后仍会保留这段文字。所以只要消息里含 {@code uk_order_no} 就是它。
     *
     * <p>消息为 null 时返回 false（保守）：宁可按"重复选课"处理，
     * 也不要因为判断不出来就去重试一个可能永远撞的索引。
     */
    private boolean isOrderNoConflict(DuplicateKeyException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("uk_order_no");
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
                orderNo = insertOrderWithRetry(studentId, courseId);
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


