package com.example.seckill.infra.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 令牌桶 Lua 脚本的"文本契约"测试。
 *
 * <p><b>为什么需要这一类测试</b>：Lua 脚本被 {@code DefaultRedisScript} 包着，
 * 单测里 Redis 是 mock 的，脚本一行都不会真的执行。所以哪怕有人把
 * {@code if elapsedMs < 0 then elapsedMs = 0 end} 删了、把 {@code math.floor}
 * 挪到写回 Redis 之前、把 TTL 算错，所有 Java 单测照样全绿。
 * 这一类测试的作用就是把脚本里的关键语句"钉"在源码里 —— 它测的不是行为，
 * 而是"这几句还在不在"。真正的行为验证只能靠真 Redis（任务书 §4 的九项实测）。
 *
 * <p>它挡得住什么：手滑删除、复制粘贴覆盖、合并冲突解错。
 * 它挡不住什么：逻辑被改成"语法还在但语义反了"（那要靠 §4 的真 Redis 实测与压测）。
 */
class RateLimitLuaScriptTest {

    private final String script = RateLimitLuaScript.TOKEN_BUCKET;

    @Test
    @DisplayName("脚本保留全部关键语句：原子读、钳位、封顶、只在返回值上取整、设 TTL")
    void scriptKeepsAllCriticalStatements() {
        // 一个 hash 同时存 tokens 与 ts —— 拆成两个 key 的话 TTL 可能不一致，
        // 会读到 tokens 存在但 ts 是 nil，补出天文数字
        assertThat(script).contains("redis.call('HMGET', key, 'tokens', 'ts')");
        assertThat(script).contains("redis.call('HSET', key, 'tokens', tokens, 'ts', nowMs)");

        // 首次访问初始化成满桶
        assertThat(script).contains("if tokens == nil then");
        assertThat(script).contains("tokens = capacity");

        // 时钟回拨保护：不回钳的话 elapsedMs 为负，令牌会凭空减少
        assertThat(script).contains("if elapsedMs < 0 then elapsedMs = 0 end");

        // 封顶：空闲一小时后不封顶会补出 540 万个令牌
        assertThat(script).contains("if tokens > capacity then tokens = capacity end");

        // TTL：容量除以补充速率 + 60 秒缓冲
        assertThat(script).contains("local ttl = math.ceil(capacity / refillPerSecond) + 60");
        assertThat(script).contains("redis.call('EXPIRE', key, ttl)");

        // 拒绝分支要给出等待时间，否则前端只能瞎猜
        assertThat(script).contains("local waitMs = math.ceil((want - tokens) / refillPerSecond * 1000)");
        assertThat(script).contains("return {0, math.floor(tokens), waitMs}");
        assertThat(script).contains("return {1, math.floor(tokens), 0}");
    }

    @Test
    @DisplayName("🔴 取整只发生在返回值上，绝不写回 Redis —— 否则小数令牌被永久丢弃")
    void floorNeverWrittenBack() {
        // 正确：写回的是 tokens（可能是 98.37），返回值才 floor
        assertThat(script).contains("redis.call('HSET', key, 'tokens', tokens, 'ts', nowMs)");

        // 错误写法一：写回时取整，0.37 个令牌每次调用都被扔掉。
        // 10/s 的桶会被削成 5/s（每 100ms 攒 1 个，取整后归零，永远攒不满）。
        assertThat(script)
                .as("把 math.floor 写进 HSET 会让补充速率腰斩，且不报任何错")
                .doesNotContain("'tokens', math.floor(tokens)");

        // 错误写法二：读出来就取整，同样丢小数
        assertThat(script).doesNotContain("tokens = math.floor(tokens)");

        // 反向确认 floor 确实存在（避免"全都不含"这种假绿）
        assertThat(script).contains("math.floor(tokens)");
    }

    @Test
    @DisplayName("常量类不可实例化")
    void cannotInstantiate() {
        assertThatThrownBy(() -> {
            var ctor = RateLimitLuaScript.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ctor.newInstance();
        })
                // 反射调用会把构造器抛的异常包一层 InvocationTargetException，
                // 所以 hasMessageContaining 查不到（外层 message 是 null），必须查根因
                .hasCauseInstanceOf(AssertionError.class)
                .hasRootCauseMessage("常量类不允许实例化");
    }
}
