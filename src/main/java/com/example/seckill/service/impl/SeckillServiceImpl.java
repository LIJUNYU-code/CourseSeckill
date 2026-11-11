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

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Service
@AllArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "seckill.strategy", havingValue = "v0-baseline", matchIfMissing = true)
public class SeckillServiceImpl implements SeckillService {

    private final CourseMapper courseMapper;
    private final CourseStockMapper courseStockMapper;
    private final SeckillOrderMapper orderMapper;

    private String generateOrderNo(Long studentId) {
        return System.currentTimeMillis() + String.format("%04d", studentId % 10000)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }


    @Override
    public SeckillResponse seckill(Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        if(now.isBefore(course.getStartTime()) || now.isAfter(course.getEndTime())) {
            throw new BusinessException(ResultCode.CONFLICT, "课程尚未开放选课");
        }

        // 3. 读库存（普通读，不加锁）—— 竞态窗口从这里开始
        CourseStock stock = courseStockMapper.selectByCourseId(courseId);

        if (stock == null) throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        if (stock.getStock() <= 0) throw new BusinessException(ResultCode.CONFLICT, "该课程名额已满");

        Long studentId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        SeckillOrder exist = orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getStudentId, studentId)
                .eq(SeckillOrder::getCourseId, courseId));

        if (exist != null) {
            if (exist.getStatus() == SeckillOrder.STATUS_SUCCESS || exist.getStatus() == SeckillOrder.STATUS_PENDING) {
                throw new BusinessException(ResultCode.CONFLICT, "你已选过该课程");
            }
            //退课重选判断
            if (exist.getStatus() == SeckillOrder.STATUS_CANCELLED) {
                // R5：把旧单改回成功，不要 INSERT
                exist.setStatus(SeckillOrder.STATUS_SUCCESS);
                exist.setFailReason(null);
                orderMapper.updateById(exist);
                courseStockMapper.absoluteUpdate(courseId, stock.getStock() - 1);
                return new SeckillResponse(exist.getOrderNo(), exist.getStatus());
            }
        }

        courseStockMapper.absoluteUpdate(courseId, stock.getStock() - 1);

        SeckillOrder order = SeckillOrder.builder()
                .orderNo(generateOrderNo(studentId))
                .studentId(studentId).courseId(courseId)
                .status(SeckillOrder.STATUS_SUCCESS).build();
        orderMapper.insert(order);   // uk_student_course 是最后一道防线，并发重复点击会在这里撞键

        return new SeckillResponse(order.getOrderNo(), order.getStatus());
    }


    @Override
    public SeckillResponse cancel(Long courseId) {
        Long studentId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        SeckillOrder order = orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getStudentId, studentId)
                .eq(SeckillOrder::getCourseId, courseId));
        if (order == null) throw new BusinessException(ResultCode.NOT_FOUND, "未找到该课程的选课记录");
        if (order.getStatus() != SeckillOrder.STATUS_SUCCESS) {
            throw new BusinessException(ResultCode.CONFLICT, "该记录当前状态无法退课");
        }
        order.setStatus(SeckillOrder.STATUS_CANCELLED);
        orderMapper.updateById(order);

        // 归还名额：sold 减 1，stock 加 1（不要超过 capacity）
        CourseStock courseStock = courseStockMapper.selectOne(new LambdaQueryWrapper<CourseStock>()
                .eq(CourseStock::getCourseId, courseId));
        int newStock = courseStock.getStock() + 1;
        if (newStock > courseStock.getCapacity()) {
            throw new BusinessException(ResultCode.CONFLICT, "名额已满，无法归还");
        }
        courseStockMapper.returnStock(courseId);
        // 提示：这里同样可以用一条 @Update 完成，但要注意别让 stock 超过 capacity
        return new  SeckillResponse(order.getOrderNo(), order.getStatus());

    }
}
