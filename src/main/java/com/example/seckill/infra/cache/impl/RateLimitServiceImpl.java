package com.example.seckill.infra.cache.impl;

import com.example.seckill.infra.cache.RateLimitResult;
import com.example.seckill.infra.cache.RateLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class RateLimitServiceImpl implements RateLimitService {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<List> rateLimitScript;

    public RateLimitServiceImpl(StringRedisTemplate stringRedisTemplate, DefaultRedisScript<List> rateLimitScript) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimitScript = rateLimitScript;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int capacity, double refillPerSecond, int want) {
        if (capacity <= 0 || refillPerSecond <= 0 || want <= 0) {
            // 配置错了：宁可放行也不要因为限流配置把全站拒掉，但必须喊出来
            log.error("限流参数非法，已跳过限流 key={} capacity={} refillPerSecond={} want={}",
                    key, capacity, refillPerSecond, want);
            return RateLimitResult.degradedPass();
        }
        if (want > capacity) {
            // 要的比桶还大，永远不可能满足；这是配置错误，不是运行时问题
            log.error("限流配置错误， want={} 大于 capacity={}, 该请求永远被拒 key={}", want, capacity, key);
            return RateLimitResult.reject(0L, 1000L);
        }
        try {
            List<?> raw = stringRedisTemplate.execute(
                    rateLimitScript,
                    List.of(key),
                    String.valueOf(capacity),
                    String.valueOf(refillPerSecond),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(want));
            if (raw == null || raw.size() < 3) {
                log.error("限流脚本返回异常，已降级放行 key={} raw={}", key, raw);
                return RateLimitResult.degradedPass();
            }
            boolean allowed = ((Number) raw.get(0)).longValue() == 1L;
            long remaining = ((Number) raw.get(1)).longValue();
            long retryAfter = ((Number) raw.get(2)).longValue();
            return allowed ? RateLimitResult.pass(remaining) : RateLimitResult.reject(remaining, retryAfter);

        }catch (Exception e) {
            // fail-open：限流是保护性设施，不是正确性设施。
            // Redis 挂了之后抢课链路本来就会返回 500，再叠一个 429 没意义。
            log.error("限流执行失败，降级放行（限流当前未生效） key={}", key, e);
            return RateLimitResult.degradedPass();
        }
    }
}
