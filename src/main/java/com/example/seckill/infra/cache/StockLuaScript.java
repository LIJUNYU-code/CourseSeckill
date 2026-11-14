package com.example.seckill.infra.cache;

/**
 * 库存扣减的 Lua 脚本。
 * <p>
 * 放在 Java 常量里而不是 resources 目录，是因为脚本极短（7 行），
 * 拆成两个文件会让"改脚本"和"改调用"分散到两处，容易只改一半。
 */
public final class StockLuaScript {

    private StockLuaScript() {
    }

    /**
     * 原子扣减库存。
     * <p>
     * KEYS[1] = 库存 key
     * ARGV[1] = 扣减数量（本项目固定为 1）
     * <p>
     * 返回值：
     *   -1 = key 不存在（未预热）
     *   -2 = 库存不足
     *  >=0 = 成功，值为扣减后的剩余量
     */
    public static final String DECREMENT = """
            local key = KEYS[1]
            local amount = tonumber(ARGV[1])
            local current = redis.call('GET', key)
            if not current then
                return -1
            end
            local stock = tonumber(current)
            if stock < amount then
                return -2
            end
            redis.call('DECRBY', key, amount)
            return stock - amount
            """;
}