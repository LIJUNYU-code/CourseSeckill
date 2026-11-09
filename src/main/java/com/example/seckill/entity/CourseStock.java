package com.example.seckill.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("course_stock")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class CourseStock {
    @TableId(value = "course_id", type = IdType.INPUT)
    private Long courseId;

    private Integer capacity;

    private Integer stock;

    private Integer sold;

    private Integer version;

    private LocalDateTime updateTime;
}
