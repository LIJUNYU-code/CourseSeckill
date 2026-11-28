package com.example.seckill.infra.cache;

/**
 * 限流相关的 Lua 脚本。
 * <p>
 * 与 {@link StockLuaScript} 同样的组织方式：脚本文本放常量类，
 * RedisConfig 里包成 DefaultRedisScript Bean。
 **/
public class RateLimitLuaScript {

    private RateLimitLuaScript() {
        throw new AssertionError("常量类不允许实例化");
    }
    /**
     * 令牌桶取令牌。
     * <p>
     * KEYS[1] = 限流 key（如 seckill:rate:global）
     * ARGV[1] = capacity        桶容量，允许的突发量
     * ARGV[2] = refillPerSecond 每秒补充的令牌数（可以是小数）
     * ARGV[3] = nowMs           当前时间戳毫秒，**由 Java 传入**，理由见任务书 §3.5
     * ARGV[4] = want            本次要取的令牌数，通常 1
     * <p>
     * 返回三元素数组 {allowed, remaining, retryAfterMs}：
     * allowed      = 1 放行 / 0 拒绝
     * remaining    = 取完之后的剩余令牌（向下取整，只用于展示）
     * retryAfterMs = 建议等待多久再试（allowed=1 时恒为 0）
     */
    public static final String TOKEN_BUCKET = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillPerSecond = tonumber(ARGV[2])
            local nowMs = tonumber(ARGV[3])
            local want = tonumber(ARGV[4])

            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])

            if tokens == nil then
              tokens = capacity
              ts = nowMs
            end

            local elapsedMs = nowMs - ts
            if elapsedMs < 0 then elapsedMs = 0 end
            tokens = tokens + (elapsedMs / 1000) * refillPerSecond
            if tokens > capacity then tokens = capacity end

            local ttl = math.ceil(capacity / refillPerSecond) + 60

            if tokens < want then
              local waitMs = math.ceil((want - tokens) / refillPerSecond * 1000)
              redis.call('HSET', key, 'tokens', tokens, 'ts', nowMs)
              redis.call('EXPIRE', key, ttl)
              return {0, math.floor(tokens), waitMs}
            end

            tokens = tokens - want
            redis.call('HSET', key, 'tokens', tokens, 'ts', nowMs)
            redis.call('EXPIRE', key, ttl)
            return {1, math.floor(tokens), 0}
            """;
}
