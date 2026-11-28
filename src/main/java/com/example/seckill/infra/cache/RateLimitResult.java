package com.example.seckill.infra.cache;



/**
 * 一次限流判定的结果。
 *
 * @param allowed      是否放行
 * @param remaining    剩余令牌（向下取整，仅用于展示/响应头）
 * @param retryAfterMs 建议等待毫秒数，allowed=true 时恒为 0
 * @param degraded     是否因为 Redis 异常而降级放行（true 表示限流当前没在起作用）
 */
public record RateLimitResult(boolean allowed, long remaining, long retryAfterMs, boolean degraded) {
    public static RateLimitResult pass(long remaining) {
        return new RateLimitResult(true, remaining, 0L, false);
    }

    public static RateLimitResult reject(long remaining, long retryAfterMs){
        return new RateLimitResult(false, remaining, retryAfterMs, false);
    }

    /** Redis 出问题时的降级结果：放行，但标记 degraded 让调用方打日志 */
    public static RateLimitResult degradedPass() {
        return new RateLimitResult(true, -1L, 0L, true);
    }
}
