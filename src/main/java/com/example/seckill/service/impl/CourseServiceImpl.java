package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.common.ResultCode;
import com.example.seckill.dto.CourseDetailResponse;
import com.example.seckill.entity.Course;
import com.example.seckill.entity.CourseStock;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.mapper.CourseMapper;
import com.example.seckill.mapper.CourseStockMapper;
import com.example.seckill.service.CourseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


@Service
@AllArgsConstructor
@Slf4j
public class CourseServiceImpl implements CourseService {
    private final CourseMapper courseMapper;

    private final CourseStockMapper courseStockMapper;

    @Override
    public CourseDetailResponse getCourseDetail(Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        }
        CourseStock courseStock = courseStockMapper.selectOne(new LambdaQueryWrapper<CourseStock>()
                .eq(CourseStock::getCourseId, courseId));
        if (courseStock == null) {
            // 数据不一致：课程在，库存行没了。不抛异常（读接口不该因为附属数据缺失就整页 500），
            // 但要留日志让人能发现。—— 这是"防御性读取"的典型写法
            log.warn("课程库存行缺失，按全 0 返回 courseId={}", courseId);
            courseStock = CourseStock.builder()
                    .courseId(courseId)
                    .capacity(0).stock(0).sold(0)
                    .build();
        }
        CourseDetailResponse res = CourseDetailResponse.builder()
                .courseId(course.getId()).courseName(course.getCourseName())
                .teacher(course.getTeacher()).credit(course.getCredit())
                .startTime(course.getStartTime()).endTime(course.getEndTime())
                .capacity(courseStock.getCapacity()).stock(courseStock.getStock())
                .sold(courseStock.getSold()).build();

        return res;


    }
}
