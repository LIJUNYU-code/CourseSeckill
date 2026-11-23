package com.example.seckill.infra.mq;

public record SeckillMessage(String msgId, String orderNo, Long studentId, Long courseId
) {}
