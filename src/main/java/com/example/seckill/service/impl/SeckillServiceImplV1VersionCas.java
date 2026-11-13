package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.common.ResultCode;
import com.example.seckill.dto.SeckillResponse;
import com.example.seckill.entity.Course;
import com.example.seckill.entity.CourseStock;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.exception.BusinessException;
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
@ConditionalOnProperty(name = "seckill.strategy", havingValue = "v1-version-cas")
public class SeckillServiceImplV1VersionCas implements SeckillService {
    private final CourseMapper courseMapper;
    private final CourseStockMapper courseStockMapper;
    private final SeckillOrderMapper orderMapper;
    private final TransactionTemplate transactionTemplate;

    private static final int MAX_ATTEMPTS = 30;
    private static final long RETRY_BACKOFF_MS = 5L;

    /**
     * 重试之间的退避等待。用随机区间而不是固定值，
     * 是为了把同时失败的一批请求错开，避免它们下一步又同时撞上同一个版本号。
     */
    private void sleepQuietly(long baseMs) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(baseMs, baseMs * 2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 恢复中断标志，不要吞掉
        }
    }

    private String generateOrderNo(Long studentId) {
        return System.currentTimeMillis() + String.format("%04d", studentId % 10000)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }


    private SeckillResponse tryOnce(Long courseId, Long studentId) {
        CourseStock stock = courseStockMapper.selectByCourseId(courseId);
        if (stock == null || stock.getStock() <= 0) {
            throw new BusinessException(ResultCode.CONFLICT, "该课程名额已满");   // 不重试
        }
        int affected = courseStockMapper.casDecrement(courseId, stock.getVersion());
        if (affected == 0) {
            return null;                     // 版本冲突 → 外层重试（本事务没写任何东西）
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
                exist.setStatus(SeckillOrder.STATUS_SUCCESS);
                exist.setFailReason(null);
                orderMapper.updateById(exist);
                return new SeckillResponse(exist.getOrderNo(), exist.getStatus());
            }
        }

        SeckillOrder order = SeckillOrder.builder()
                .orderNo(generateOrderNo(studentId))
                .studentId(studentId)
                .courseId(courseId)
                .status(SeckillOrder.STATUS_SUCCESS)
                .build();
        orderMapper.insert(order);           // 撞 uk_student_course 会抛 DuplicateKeyException

        return new SeckillResponse(order.getOrderNo(), order.getStatus());
    }


    @Override
    public SeckillResponse seckill(Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(course.getStartTime()) || now.isAfter(course.getEndTime()))
            throw new BusinessException(ResultCode.CONFLICT, "课程尚未开放");

        Long studentId = (Long) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            SeckillResponse resp = transactionTemplate.execute(status -> tryOnce(courseId, studentId));
            if (resp != null) {
                return resp;
            }
            log.debug("CAS 版本冲突，第 {} 次重试 courseId={} studentId={}", attempt, courseId, studentId);
            sleepQuietly(RETRY_BACKOFF_MS);
        }
        throw new BusinessException(ResultCode.CONFLICT, "系统繁忙，请重试");
    }


    @Override
    @Transactional
    public SeckillResponse cancel(Long courseId) {
        Long studentId = (Long) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        SeckillOrder order = orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getStudentId, studentId)
                .eq(SeckillOrder::getCourseId, courseId));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "未找到该课程的选课记录");
        }
        if (order.getStatus() != SeckillOrder.STATUS_SUCCESS) {
            throw new BusinessException(ResultCode.CONFLICT, "该记录当前状态无法退课");
        }
        order.setStatus(SeckillOrder.STATUS_CANCELLED);
        orderMapper.updateById(order);

        courseStockMapper.returnStock(courseId);
        return new SeckillResponse(order.getOrderNo(), order.getStatus());
    }
}
