package com.example.seckill.service;

import com.example.seckill.dto.SeckillResponse;

public interface SeckillService {


    public SeckillResponse seckill(Long courseId);


    public SeckillResponse cancel(Long courseId);
}
