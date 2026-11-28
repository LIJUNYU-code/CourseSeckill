package com.example.seckill.infra.cache.impl;

import com.example.seckill.common.ResultCode;
import com.example.seckill.entity.CourseStock;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.mapper.CourseStockMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StockCacheServiceImpl 单元测试 —— 覆盖 v2 引入的 Redis 库存门禁。
 *
 * <p>测什么：把 Lua 的三种返回值（-1 / -2 / &gt;=0）翻译成什么业务结果、
 * 异常时是否如实抛出而不是伪装成"名额已满"、预热到底写的是 stock 还是 capacity。
 *
 * <p>不测什么：Lua 脚本本身的原子性（那要用真 Redis，见 docs/阶段4-任务书-v2-redis-lua.md §1.3.1
 * 的六分支实测）与并发下的表现（见 docs/压测报告-v2.md）。
 */
@ExtendWith(MockitoExtension.class)
class StockCacheServiceImplTest {

    /** 项目里这个 Bean 的实际类型是 DefaultRedisScript<Long>，Mockito 需要具体类型才能打桩。 */
    @SuppressWarnings("unchecked")
    private final DefaultRedisScript<Long> script = (DefaultRedisScript<Long>) org.mockito.Mockito.mock(DefaultRedisScript.class);

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private CourseStockMapper courseStockMapper;

    private StockCacheServiceImpl stockCacheService;

    @BeforeAll
    static void initNothing() {
        // 本测试类不构造 LambdaQueryWrapper，不需要 TableInfo 预热。
        // 保留方法是为了和项目里其它测试类的结构一致，一眼能看出"这里刻意不需要"。
    }

    @BeforeEach
    void setUp() {
        // 手写构造而不是 @InjectMocks：script 是泛型接口的 mock，@InjectMocks 有时会挑错构造器。
        stockCacheService = new StockCacheServiceImpl(stringRedisTemplate, script, courseStockMapper);
    }

    // ==================== tryDecrement：Lua 三种返回值的翻译 ====================

    @Test
    @DisplayName("Lua 返回 -1（key 不存在，未预热）→ 原样返回 -1，由上层决定是否预热重试")
    void tryDecrement_keyMissing_returnsMinusOne() {
        when(stringRedisTemplate.execute(eq(script), anyList(), any())).thenReturn(-1L);

        Long left = stockCacheService.tryDecrement(88L);

        assertThat(left).isEqualTo(-1L);

        // 顺带断言 key 拼对了：拼成 "seckill:stock88" 或带多余冒号都会让预热和扣减对不上，
        // 但那种错在单测里不会报错，只会在线上表现为"永远未预热"。这里把它钉死。
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(eq(script), keys.capture(), any());
        assertThat(keys.getValue()).containsExactly("seckill:stock:88");
    }

    @Test
    @DisplayName("Lua 返回 -2（库存不足）→ 原样返回 -2")
    void tryDecrement_notEnough_returnsMinusTwo() {
        when(stringRedisTemplate.execute(eq(script), anyList(), any())).thenReturn(-2L);

        assertThat(stockCacheService.tryDecrement(88L)).isEqualTo(-2L);
    }

    @Test
    @DisplayName("Lua 返回 0（正好卖完最后一个）→ 返回 0，不能当成失败")
    void tryDecrement_lastOne_returnsZero() {
        // 🔴 这条最容易写错：Lua 里 stock < amount 才返回 -2，
        //    所以"剩 1 个，扣 1 个"返回的是 0（成功）而不是 -2（失败）。
        //    如果把判据写成 left <= 0 就少卖一个名额。
        when(stringRedisTemplate.execute(eq(script), anyList(), any())).thenReturn(0L);

        Long left = stockCacheService.tryDecrement(88L);

        assertThat(left).isZero();
        assertThat(left).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("Lua 抛异常 → 抛 500 业务异常，不能伪装成「名额已满」")
    void tryDecrement_scriptThrows_throwsInternalError() {
        when(stringRedisTemplate.execute(eq(script), anyList(), any()))
                .thenThrow(new RuntimeException("Redis 连接断了"));

        assertThatThrownBy(() -> stockCacheService.tryDecrement(88L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.INTERNAL_ERROR))
                // 🔴 关键：如果这里吞成 -2，Redis 挂掉时前端会显示"名额已满"，
                //    把真正的故障藏起来，排查时会往完全错误的方向找。
                .hasMessage("系统繁忙，请重试");
    }

    @Test
    @DisplayName("Lua 返回 null（脚本没给返回值）→ 抛 500，而不是拆箱 NPE")
    void tryDecrement_nullResult_throwsInternalError() {
        when(stringRedisTemplate.execute(eq(script), anyList(), any())).thenReturn(null);

        assertThatThrownBy(() -> stockCacheService.tryDecrement(88L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.INTERNAL_ERROR));
    }

    // ==================== preheat：回归测试，防"预热写 capacity" ====================

    @Test
    @DisplayName("预热写入的是 stock（剩余）不是 capacity（总名额）")
    void preheat_writesStockNotCapacity() {
        // 🔴 这是 v2 阶段真实踩过的坑：写成 getCapacity() 会让 Redis 里的库存永远是满的，
        //    因为 capacity 不随抢课减少 —— 每次预热都把已经卖掉的份额"变回来"，超卖到天亮。
        //    数据故意构造成 capacity != stock，这样写错就必然测出来。
        CourseStock stockRow = CourseStock.builder()
                .courseId(88L).capacity(100).stock(37).sold(63).version(0)
                .build();
        when(courseStockMapper.selectByCourseId(88L)).thenReturn(stockRow);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        stockCacheService.preheat(88L);

        verify(valueOps).set("seckill:stock:88", "37");
        // 反过来钉一下：绝不能写成 100
        verify(valueOps, never()).set(any(), eq("100"));
    }

    @Test
    @DisplayName("库存行不存在 → 跳过预热，不写 Redis 也不报错")
    void preheat_stockRowMissing_skips() {
        when(courseStockMapper.selectByCourseId(99999L)).thenReturn(null);

        stockCacheService.preheat(99999L);

        // 什么都不该发生：不打桩 opsForValue() 也不会 NPE，证明真的提前 return 了
        verify(stringRedisTemplate, never()).opsForValue();
    }

    // ==================== preheatIfAbsent：阶段 11 冷启动惊群修复 ====================

    @Test
    @DisplayName("preheatIfAbsent：key 不存在 → 用 SETNX 写入 stock（不是 capacity）")
    void preheatIfAbsent_keyMissing_writesWithSetnx() {
        // 数据同样故意让 capacity != stock：写错成 capacity 会立刻测出来。
        CourseStock stockRow = CourseStock.builder()
                .courseId(88L).capacity(100).stock(37).sold(63).version(0)
                .build();
        when(courseStockMapper.selectByCourseId(88L)).thenReturn(stockRow);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent("seckill:stock:88", "37")).thenReturn(true);

        stockCacheService.preheatIfAbsent(88L);

        verify(valueOps).setIfAbsent("seckill:stock:88", "37");
        // 🔴 反向断言：这条是阶段 11 的核心。只要这里用了覆盖写 set()，
        //    冷启动时后到的请求就会把前面已经扣减的库存重置回满额，名额复活 → 超额受理。
        //    把 never() 钉在这里，谁把 SETNX 改回 SET，单测立刻变红。
        verify(valueOps, never()).set(any(), any());
    }

    @Test
    @DisplayName("preheatIfAbsent：key 已存在 → SETNX 返回 false，一个字节都不写")
    void preheatIfAbsent_keyExists_doesNotOverwrite() {
        // 这个场景就是惊群的现场：第一个请求已经写好了（可能还扣过了），
        // 后面 199 个请求也走进 -1 分支，此时必须什么都不做。
        CourseStock stockRow = CourseStock.builder()
                .courseId(88L).capacity(100).stock(100).sold(0).version(0)
                .build();
        when(courseStockMapper.selectByCourseId(88L)).thenReturn(stockRow);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent("seckill:stock:88", "100")).thenReturn(false);

        stockCacheService.preheatIfAbsent(88L);

        verify(valueOps).setIfAbsent("seckill:stock:88", "100");
        // 返回 false 说明 key 已存在，此时若再调 set() 就是覆盖写，等于把库存拉回满额。
        verify(valueOps, never()).set(any(), any());
    }

    @Test
    @DisplayName("preheatIfAbsent：库存行不存在 → 跳过，不碰 Redis")
    void preheatIfAbsent_stockRowMissing_skips() {
        when(courseStockMapper.selectByCourseId(99999L)).thenReturn(null);

        stockCacheService.preheatIfAbsent(99999L);

        // 不打桩 opsForValue() 也不会 NPE，证明真的在查库后就 return 了。
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("全量预热 → 每门课写一条，条数等于查到的行数")
    void preheatAll_writesEveryCourse() {
        when(courseStockMapper.selectList(null)).thenReturn(List.of(
                CourseStock.builder().courseId(1L).capacity(100).stock(100).sold(0).build(),
                CourseStock.builder().courseId(2L).capacity(50).stock(12).sold(38).build()));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        stockCacheService.preheatAll();

        verify(valueOps).set("seckill:stock:1", "100");
        verify(valueOps).set("seckill:stock:2", "12");
    }

    // ==================== incrementIfPresent：退课归还的安全阀 ====================

    @Test
    @DisplayName("key 不存在时退课归还不创建 key（否则会凭空造出库存）")
    void incrementIfPresent_keyMissing_doesNothing() {
        // 🔴 为什么不能用 increment：Redis 的 INCR 在 key 不存在时会创建并置为 1。
        //    退课时如果这门课从没预热过，INCR 会造出一个 stock=1 的 key，
        //    下一次抢课就会误以为"还剩 1 个"，凭空多卖。
        when(stringRedisTemplate.hasKey("seckill:stock:88")).thenReturn(false);

        stockCacheService.incrementIfPresent(88L);

        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("key 存在时退课归还 +1")
    void incrementIfPresent_keyExists_increments() {
        when(stringRedisTemplate.hasKey("seckill:stock:88")).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        stockCacheService.incrementIfPresent(88L);

        verify(valueOps).increment("seckill:stock:88");
    }

    // ==================== getStock / clear ====================

    @Test
    @DisplayName("getStock：key 不存在返回 null（不是 0，两者语义不同）")
    void getStock_missing_returnsNull() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("seckill:stock:88")).thenReturn(null);

        // null = "没预热"，0 = "卖完了"。对账时把两者混为一谈会误判成"库存一致"。
        assertThat(stockCacheService.getStock(88L)).isNull();
    }

    @Test
    @DisplayName("getStock：有值返回数字")
    void getStock_present_returnsNumber() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("seckill:stock:88")).thenReturn("42");

        assertThat(stockCacheService.getStock(88L)).isEqualTo(42L);
    }

    @Test
    @DisplayName("clear：删掉的是带前缀的完整 key")
    void clear_deletesPrefixedKey() {
        stockCacheService.clear(88L);

        verify(stringRedisTemplate).delete("seckill:stock:88");
    }
}
