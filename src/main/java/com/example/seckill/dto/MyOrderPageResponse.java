package com.example.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MyOrderPageResponse {

    private Long total;

    private Integer page;

    private Integer size;

    private List<OrderResponse> records;
}
