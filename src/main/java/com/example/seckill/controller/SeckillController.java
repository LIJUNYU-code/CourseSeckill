package com.example.seckill.controller;

import com.example.seckill.common.Result;
import com.example.seckill.dto.SeckillResponse;
import com.example.seckill.service.SeckillService;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
@RequestMapping("/api/seckill")
public class SeckillController {

    private final SeckillService seckillService;

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/{courseId}")
    public Result<SeckillResponse> seckill(@PathVariable Long courseId) {
        SeckillResponse seckill = seckillService.seckill(courseId);
        return Result.success(seckill);
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/{courseId}/cancel")
    public Result<SeckillResponse> cancel(@PathVariable Long courseId) {
        SeckillResponse cancel = seckillService.cancel(courseId);
        return Result.success(cancel);
    }
}
