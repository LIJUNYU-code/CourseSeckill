package com.example.seckill.service;

import com.example.seckill.dto.CourseDetailResponse;

public interface CourseService {

    CourseDetailResponse getCourseDetail(Long courseId);
}
