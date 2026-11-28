package com.example.seckill.infra.cache;


/**
 * 限流服务。
 * <p>
 * 实现放在 Redis 上而不是本地内存：多实例下本地计数器会把配额放大 N 倍。
 */
public interface RateLimitService {

    /**
     * 尝试获取令牌。
     *
     * @param key              限流 key，如 seckill:rate:global
     * @param capacity         桶容量
     * @param refillPerSecond  每秒补充数
     * @param want             本次要几个令牌，通常 1
     * @return 判定结果，永不为 null（Redis 挂了也返回一个"放行"的结果，见实现里的 fail-open）
     */
    RateLimitResult tryAcquire(String key, int capacity, double refillPerSecond,int want);

}
