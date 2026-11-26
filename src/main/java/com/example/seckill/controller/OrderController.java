package com.example.seckill.controller;


import com.example.seckill.common.Result;
import com.example.seckill.dto.MyOrderPageResponse;
import com.example.seckill.dto.OrderResponse;
import com.example.seckill.service.OrderService;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
@RequestMapping("/api/order")
public class OrderController {

    private final OrderService orderService;

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/my")
    public Result<MyOrderPageResponse> query(@RequestParam(defaultValue = "1") Integer page,
                                             @RequestParam(defaultValue = "10") Integer size) {
        // 单个学生最多 100 条订单，超过这个数的 size 没有意义，
        // 但可以让调用方一次拉 10 万行 JSON 把内存打爆 —— 兜一下
        if (size == null || size < 1) size = 10;
        if (size > 100) size = 100;
        MyOrderPageResponse res = orderService.queryOrders(page, size);
        return Result.success(res);
    }


    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/{orderNo}")
    public Result<OrderResponse> queryOrders(@PathVariable String orderNo) {
        OrderResponse res = orderService.query(orderNo);
        return Result.success(res);
    }
}
