package com.example.seckill.service;

import com.example.seckill.dto.MyOrderPageResponse;
import com.example.seckill.dto.OrderResponse;

public interface OrderService {

    MyOrderPageResponse queryOrders(Integer page, Integer size);

    OrderResponse query(String orderNo);
}
