package com.example.seckill.infra.cache;

/**
 * Redis 库存缓存。
 * <p>
 * 约定：所有方法都不吞异常。Redis 不可用时直接抛出，
 * 由调用方决定怎么处理（本项目选择快失败，见任务书 §9.5）。
 * <p>
 * 不在这里做业务判断，只负责"读到什么就返回什么"——
 * 把"怎么说"留给上层，是让补偿逻辑能被测试的前提。
 */
public interface StockCacheService {

    /**
     * 从数据库读 stock，覆盖写入 Redis。
     * <p>
     * 幂等：重复调用以数据库为准。用 SET 不用 SETNX。
     * 🔴 用 getStock() 不是 getCapacity()，见任务书 §5.3。
     */
    void preheat(Long courseId);

    /** 预热全部课程（启动时、手动修数据时用）。 */
    void preheatAll();

    /**
     * Lua 原子扣减，返回原始码不做业务判断。
     * <p>
     * -1 = 未预热；-2 = 库存不足；>=0 = 成功，值为剩余库存。
     */
    Long tryDecrement(Long courseId);

    /** 归还 1 个名额（业务失败时的补偿）。 */
    void increment(Long courseId);

    /** 读 Redis 当前余量，key 不存在返回 null。 */
    Long getStock(Long courseId);

    /** 删除 key，用于复位。 */
    void clear(Long courseId);
    /** 归还名额，但仅在 key 已存在时。用于退课场景。 */
    void incrementIfPresent(Long courseId);
}
