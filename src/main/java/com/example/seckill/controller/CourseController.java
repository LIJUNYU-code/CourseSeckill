package com.example.seckill.controller;

import com.example.seckill.common.Result;
import com.example.seckill.dto.CourseDetailResponse;
import com.example.seckill.service.CourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/course")
@AllArgsConstructor
@Tag(name = "课程管理", description = "课程详情与库存查询")   // ← 挪到类上，改名
public class CourseController {

    private CourseService courseService;

    @GetMapping("/{courseId}")
    @Operation(summary = "查询课程详情", description = "返回课程基本信息与库存；课程不存在返回 404")
    public Result<CourseDetailResponse> getCourseDetail(@PathVariable Long courseId) {
        return Result.success(courseService.getCourseDetail(courseId));
    }

    @GetMapping
    @Operation(summary = "课程列表", description = "返回全部课程与当前库存，供页面渲染选课列表")
    public Result<List<CourseDetailResponse>> list() {
        return Result.success(courseService.listCourses());
    }
}