package com.example.seckill.infra.cache.impl;

import com.example.seckill.infra.cache.RateLimitResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimitServiceImpl 单元测试 —— 覆盖令牌桶脚本返回值的翻译与 fail-open 降级。
 *
 * <p>测什么：Lua 返回的 {@code {allowed, remaining, retryAfterMs}} 三元组怎么变成
 * {@link RateLimitResult}、参数配错时走哪条路、Redis 抛异常时到底放行还是拒绝。
 *
 * <p>不测什么：Lua 脚本自身的原子性与补令牌的数学（那要用真 Redis，见
 * docs/阶段10-任务书-限流.md §4 的九项实测）；多实例共享同一个桶（那要靠压测）。
 *
 * <p><b>为什么单独强调 fail-open</b>：限流是保护性设施，不是正确性设施。
 * Redis 挂了之后抢课链路本来就会 500，此时如果限流也 fail-closed 变成全站 429，
 * 等于把"一个组件抖动"放大成"整个服务不可用"。用例 {@code redisThrows_failsOpen}
 * 就是钉死这个决定 —— 它变红说明有人把降级改成了拒绝。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitServiceImplTest {

    private static final String KEY = "seckill:rate:student:1";

    /** 项目里这个 Bean 的实际类型是 DefaultRedisScript&lt;List&gt;，Mockito 需要具体类型才能打桩。 */
    @SuppressWarnings("unchecked")
    private final DefaultRedisScript<List> script =
            (DefaultRedisScript<List>) org.mockito.Mockito.mock(DefaultRedisScript.class);

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private RateLimitServiceImpl rateLimitService;

    @BeforeAll
    static void initNothing() {
        // 本测试类不构造 LambdaQueryWrapper，不需要 TableInfo 预热。
        // 保留方法是为了和项目里其它测试类的结构一致，一眼能看出"这里刻意不需要"。
    }

    @BeforeEach
    void setUp() {
        // 手写构造而不是 @InjectMocks：script 是泛型接口的 mock，@InjectMocks 有时会挑错构造器。
        rateLimitService = new RateLimitServiceImpl(stringRedisTemplate, script);
    }

    /** Lua 返回 {1, remaining, 0} 时是"放行"。 */
    private void stubScriptReturns(Object... values) {
        when(stringRedisTemplate.execute(eq(script), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(values));
    }

    // ==================== 正常路径：三元组的翻译 ====================

    @Test
    @DisplayName("Lua 返回 {1,4,0} → 放行，remaining=4，不是降级")
    void allowed_returnsPass() {
        stubScriptReturns(1L, 4L, 0L);

        RateLimitResult r = rateLimitService.tryAcquire(KEY, 5, 1.0, 1);

        assertThat(r.allowed()).isTrue();
        assertThat(r.remaining()).isEqualTo(4L);
        assertThat(r.retryAfterMs()).isZero();
        assertThat(r.degraded())
                .as("degraded 必须为 false：它是「限流没生效」的信号，正常放行不能误报")
                .isFalse();
    }

    @Test
    @DisplayName("Lua 返回 {0,0,1000} → 拒绝，remaining=0，retryAfterMs=1000")
    void rejected_returnsRejectWithRetryAfter() {
        stubScriptReturns(0L, 0L, 1000L);

        RateLimitResult r = rateLimitService.tryAcquire(KEY, 5, 1.0, 1);

        assertThat(r.allowed()).isFalse();
        assertThat(r.remaining()).isZero();
        assertThat(r.retryAfterMs()).isEqualTo(1000L);
        assertThat(r.degraded()).isFalse();
    }

    // ==================== 最值钱的一条：fail-open ====================

    @Test
    @DisplayName("🔴 Redis 抛异常 → 降级放行（fail-open），绝不抛给上层")
    void redisThrows_failsOpen() {
        when(stringRedisTemplate.execute(eq(script), anyList(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Redis 挂了"));

        RateLimitResult r = rateLimitService.tryAcquire(KEY, 5, 1.0, 1);

        assertThat(r.allowed())
                .as("Redis 故障时限流必须放行：fail-closed 会把一个组件抖动放大成全站 429")
                .isTrue();
        assertThat(r.degraded())
                .as("必须标记 degraded=true，否则运维看不出限流其实没在工作")
                .isTrue();
        assertThat(r.remaining()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Lua 返回 null 或元素不足 3 个 → 同样降级放行，不抛 NullPointerException")
    void malformedScriptResult_failsOpen() {
        when(stringRedisTemplate.execute(eq(script), anyList(), any(), any(), any(), any()))
                .thenReturn(null);

        RateLimitResult nullResult = rateLimitService.tryAcquire(KEY, 5, 1.0, 1);
        assertThat(nullResult.allowed()).isTrue();
        assertThat(nullResult.degraded()).isTrue();

        // 只有两个元素：拿 raw.get(2) 会 IndexOutOfBounds，所以实现里先判了 size < 3
        when(stringRedisTemplate.execute(eq(script), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(1L, 4L));

        RateLimitResult shortResult = rateLimitService.tryAcquire(KEY, 5, 1.0, 1);
        assertThat(shortResult.allowed()).isTrue();
        assertThat(shortResult.degraded()).isTrue();
    }

    // ==================== 配置错误：不碰 Redis，直接给结论 ====================

    @Test
    @DisplayName("capacity<=0 / refillPerSecond<=0 / want<=0 → 降级放行，且一次 Redis 都不调")
    void illegalParameters_degradeWithoutTouchingRedis() {
        assertThat(rateLimitService.tryAcquire(KEY, 0, 1.0, 1).degraded()).isTrue();
        assertThat(rateLimitService.tryAcquire(KEY, 5, 0.0, 1).degraded()).isTrue();
        assertThat(rateLimitService.tryAcquire(KEY, 5, 1.0, 0).degraded()).isTrue();
        assertThat(rateLimitService.tryAcquire(KEY, -1, -1.0, -1).degraded()).isTrue();

        verify(stringRedisTemplate, never()).execute(any(), anyList(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("want > capacity → 直接拒绝（配置错，永远满足不了），不碰 Redis")
    void wantGreaterThanCapacity_rejectsWithoutTouchingRedis() {
        RateLimitResult r = rateLimitService.tryAcquire(KEY, 5, 1.0, 10);

        assertThat(r.allowed()).isFalse();
        assertThat(r.retryAfterMs())
                .as("要的比桶还大，等多久都没用；给个固定 1 秒而不是算出一个巨大的等待时间")
                .isEqualTo(1000L);

        verify(stringRedisTemplate, never()).execute(any(), anyList(), any(), any(), any(), any());
    }

    // ==================== 传给 Lua 的参数对不对 ====================

    @Test
    @DisplayName("四个 ARGV 按 (capacity, refillPerSecond, 当前毫秒, want) 顺序传给脚本")
    void passesArgumentsInScriptOrder() {
        stubScriptReturns(1L, 4L, 0L);

        long before = System.currentTimeMillis();
        rateLimitService.tryAcquire(KEY, 7, 2.5, 1);
        long after = System.currentTimeMillis();

        ArgumentCaptor<String> capacity = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> refill = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> nowMs = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> want = ArgumentCaptor.forClass(String.class);

        verify(stringRedisTemplate).execute(eq(script), eq(List.of(KEY)),
                capacity.capture(), refill.capture(), nowMs.capture(), want.capture());

        assertThat(capacity.getValue()).isEqualTo("7");
        assertThat(refill.getValue()).isEqualTo("2.5");
        assertThat(want.getValue()).isEqualTo("1");

        // 时间戳必须由 Java 传：用 Lua 的 redis.call('TIME') 就没法在单测里控制，
        // 而且部分托管 Redis 禁用非确定性命令。
        long ts = Long.parseLong(nowMs.getValue());
        assertThat(ts).isBetween(before, after);
    }
}
