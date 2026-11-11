package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.seckill.common.ResultCode;
import com.example.seckill.dto.MyOrderPageResponse;
import com.example.seckill.dto.OrderResponse;
import com.example.seckill.entity.Course;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.mapper.CourseMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.service.OrderService;
import lombok.AllArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@AllArgsConstructor
@Service
public class OrderServiceImpl implements OrderService {

    private final SeckillOrderMapper orderMapper;

    private final CourseMapper courseMapper;

    public String getStatusText(Integer status) {
        if (status == null) return "";
        return switch (status) {
            case 1 -> "已成功";
            case 2 -> "已失败";
            case 3 -> "已退课";
            default -> "待处理";
        };
    }

    @Override
    public MyOrderPageResponse queryOrders(Integer page, Integer size) {

        Long currentId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();
        Page<SeckillOrder> seckillOrderPage = orderMapper.selectPage(new Page<SeckillOrder>(page, size), new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getStudentId, currentId)
                .orderByDesc(SeckillOrder::getCreateTime));


        // 空列表是正常业务状态，返回 total=0 + 空数组，不要抛 404
        if (seckillOrderPage.getRecords().isEmpty()) {
            return MyOrderPageResponse.builder()
                    .total(0L).page(page).size(size).records(List.of()).build();
        }

        List<OrderResponse> records = seckillOrderPage.getRecords().stream().map(s ->
        {
            Course course = courseMapper.selectById(s.getCourseId());
            if (course == null) throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
            return OrderResponse.builder().orderNo(s.getOrderNo())
                    .courseId(s.getCourseId())
                    .courseName(course.getCourseName()).failReason(s.getFailReason())
                    .statusText(getStatusText(s.getStatus())).status(s.getStatus())
                    .createTime(s.getCreateTime())
                    .updateTime(s.getUpdateTime())
                    .build();
        }).toList();

        MyOrderPageResponse myOrderPageResponse = new MyOrderPageResponse();
        myOrderPageResponse.setTotal(seckillOrderPage.getTotal());
        myOrderPageResponse.setPage(page);
        myOrderPageResponse.setSize(size);
        myOrderPageResponse.setRecords(records);


        return myOrderPageResponse;


    }

    @Override
    public OrderResponse query(String orderNo) {
        Long currentId = (Long) Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getPrincipal();

        SeckillOrder seckillOrder = orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderNo, orderNo));
        if (seckillOrder == null) throw new BusinessException(ResultCode.NOT_FOUND, "暂无选课记录");

        if (!seckillOrder.getStudentId().equals(currentId))
            throw new BusinessException(ResultCode.NOT_FOUND, "暂无选课记录" );
        Course course = courseMapper.selectOne(new LambdaQueryWrapper<Course>().eq(Course::getId, seckillOrder.getCourseId()));
        if (course == null) throw new BusinessException(ResultCode.NOT_FOUND, "暂无选课记录");
        OrderResponse res = OrderResponse.builder().orderNo(orderNo)
                .courseId(seckillOrder.getCourseId())
                .courseName(course.getCourseName())
                .failReason(seckillOrder.getFailReason())
                .statusText(getStatusText(seckillOrder.getStatus()))
                .status(seckillOrder.getStatus())
                .createTime(seckillOrder.getCreateTime()).updateTime(seckillOrder.getUpdateTime()).build();
        return res;
    }
}
