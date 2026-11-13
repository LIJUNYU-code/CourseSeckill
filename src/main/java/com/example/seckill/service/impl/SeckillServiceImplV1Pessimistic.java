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

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;


@Service
@AllArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "seckill.strategy", havingValue = "v1-pessimistic")
public class SeckillServiceImplV1Pessimistic implements SeckillService {
    private final CourseMapper courseMapper;
    private final CourseStockMapper courseStockMapper;
    private final SeckillOrderMapper orderMapper;

    private String generateOrderNo(Long studentId) {
        return System.currentTimeMillis() + String.format("%04d", studentId % 10000)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    @Override
    @Transactional
    public SeckillResponse seckill(Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(course.getStartTime()) || now.isAfter(course.getEndTime())) {
            throw new BusinessException(ResultCode.CONFLICT, "课程尚未开放选课");
        }


        CourseStock stock = courseStockMapper.selectForUpdate(courseId);
        if (stock == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        }
        if (stock.getStock() <= 0) {
            throw new BusinessException(ResultCode.CONFLICT, "该课程名额已满");
        }

        courseStockMapper.absoluteUpdate(courseId, stock.getStock() - 1);

        Long studentId = (Long) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        SeckillOrder exist = orderMapper.selectOne((new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getStudentId, studentId)
                .eq(SeckillOrder::getCourseId, courseId)));
        if (exist != null) {
            if (exist.getStatus() == SeckillOrder.STATUS_SUCCESS
                    || exist.getStatus() == SeckillOrder.STATUS_PENDING) {
                // 抛异常 → 事务回滚 → 上面那次扣减自动撤销
                throw new BusinessException(ResultCode.CONFLICT, "你已选过该课程");
            }
            if (exist.getStatus() == SeckillOrder.STATUS_CANCELLED) {
                // R5：退课后重选，把旧单改回成功，不要 INSERT
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
        // 并发重复点击会在这里撞 uk_student_course
        // → DuplicateKeyException → 事务回滚 → 库存自动还回去
        orderMapper.insert(order);

        return new SeckillResponse(order.getOrderNo(), order.getStatus());
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
