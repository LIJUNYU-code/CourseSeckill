package com.example.seckill.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OrderResponse {

    private String orderNo;

    private Long courseId;

    private String courseName;

    private String failReason;

    private String statusText;

    private Integer status;

    private LocalDateTime createTime;


    private LocalDateTime updateTime;



}
