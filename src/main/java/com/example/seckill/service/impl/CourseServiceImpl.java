package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.common.ResultCode;
import com.example.seckill.dto.CourseDetailResponse;
import com.example.seckill.entity.Course;
import com.example.seckill.entity.CourseStock;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.mapper.CourseMapper;
import com.example.seckill.mapper.CourseStockMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.service.CourseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
@AllArgsConstructor
@Slf4j
public class CourseServiceImpl implements CourseService {
    private final CourseMapper courseMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final CourseStockMapper courseStockMapper;

    private CourseDetailResponse assemble(Course course, CourseStock stock, Integer myStatus) {
        if (stock == null) {
            log.warn("课程库存行缺失，按全 0 返回 courseId={}", course.getId());
            stock = CourseStock.builder().courseId(course.getId())
                    .capacity(0).stock(0).sold(0).build();
        }
        return CourseDetailResponse.builder()
                .courseId(course.getId()).courseName(course.getCourseName())
                .teacher(course.getTeacher()).credit(course.getCredit())
                .startTime(course.getStartTime()).endTime(course.getEndTime())
                .capacity(stock.getCapacity()).stock(stock.getStock()).sold(stock.getSold())
                .myOrderStatus(myStatus).build();
    }

    private Map<Long, Integer> myStatusMap() {
        Long studentId = currentStudentId();
        if (studentId == null) return Map.of();
        return seckillOrderMapper.selectList(new LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getStudentId, studentId))
                .stream().collect(Collectors.toMap(SeckillOrder::getCourseId,
                        SeckillOrder::getStatus, (a, b) -> a));
    }

    private Long currentStudentId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) return null;
        boolean isStudent = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch("ROLE_STUDENT"::equals);
        return isStudent ? id : null;
    }


    @Override
    public CourseDetailResponse getCourseDetail(Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        }
        CourseStock stock = courseStockMapper.selectOne(
                new LambdaQueryWrapper<CourseStock>().eq(CourseStock::getCourseId, courseId));

        // 单门课只查这一门，不用把该学生的全部订单都捞出来
        Integer myStatus = null;
        Long studentId = currentStudentId();
        if (studentId != null) {
            SeckillOrder one = seckillOrderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
                    .eq(SeckillOrder::getStudentId, studentId)
                    .eq(SeckillOrder::getCourseId, courseId));
            myStatus = (one == null) ? null : one.getStatus();
        }
        return assemble(course, stock, myStatus);   // assemble 里已有 stock==null 兜底，别写两遍
    }



    @Override
    public List<CourseDetailResponse> listCourses() {
        List<Course> courses = courseMapper.selectList(
                new LambdaQueryWrapper<Course>().orderByAsc(Course::getId)
        );
        Map<Long, CourseStock> stockMap = courseStockMapper.selectList(null).stream()
                .collect(Collectors.toMap(CourseStock::getCourseId, s -> s , (a, b) -> a));
        Map<Long, Integer> statusMap = myStatusMap();
        return courses.stream().map(
                c-> assemble(c, stockMap.get(c.getId()), statusMap.get(c.getId()))).toList();

    }
}
