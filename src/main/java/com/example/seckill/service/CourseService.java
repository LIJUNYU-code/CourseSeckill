package com.example.seckill.service;

import com.example.seckill.dto.CourseDetailResponse;

import java.util.List;

public interface CourseService {

    CourseDetailResponse getCourseDetail(Long courseId);

    List<CourseDetailResponse> listCourses();
}
