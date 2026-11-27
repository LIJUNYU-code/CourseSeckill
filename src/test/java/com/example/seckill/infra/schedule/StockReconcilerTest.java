package com.example.seckill.infra.schedule;

import com.example.seckill.entity.CourseStock;
import com.example.seckill.infra.cache.StockCacheService;
import com.example.seckill.mapper.CourseStockMapper;
import com.example.seckill.support.TableInfoTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StockReconciler} 的单元测试。
 *
 * <p>整个对账功能只有一条不能破的规矩：<b>只修偏高，不修偏低</b>。
 * <ul>
 *   <li>偏高 = Redis 里的名额比数据库多 = 虚报名额 = 会超卖 → <b>必须收回</b>；</li>
 *   <li>偏低 = Redis 里的名额比数据库少 = 只是浪费名额 = 安全方向 → <b>绝不能修</b>，
 *       因为"偏低"很可能只是某个请求刚扣了 Redis、MySQL 还没提交，此时把它拉回 db 值，
 *       等那个请求提交后 Redis 反而变成偏高，凭空造出超卖。</li>
 * </ul>
 * 用例 3 和用例 6 就是把这条规矩钉死在代码里的那两条。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockReconcilerTest {

    @Mock
    private CourseStockMapper courseStockMapper;

    @Mock
    private StockCacheService stockCacheService;

    private StockReconciler reconciler;

    @BeforeAll
    static void initTableInfo() {
        TableInfoTestSupport.initAll();
    }

    @BeforeEach
    void setUp() {
        reconciler = new StockReconciler(courseStockMapper, stockCacheService, 20);
    }

    private StockReconciler newReconciler(int maxFix) {
        return new StockReconciler(courseStockMapper, stockCacheService, maxFix);
    }

    /** 造一行库存。 */
    private CourseStock row(long courseId, int stock, int sold) {
        CourseStock s = new CourseStock();
        s.setCourseId(courseId);
        s.setCapacity(stock + sold);
        s.setStock(stock);
        s.setSold(sold);
        return s;
    }

    // ---------- 用例 1：全部一致 ----------

    @Test
    @DisplayName("用例1 全部一致：consistent=3，fixed=0，一次 preheat 都不调")
    void allConsistent_noFix() {
        when(courseStockMapper.selectList(null))
                .thenReturn(List.of(row(1, 100, 0), row(2, 50, 50), row(3, 0, 100)));
        when(stockCacheService.getStock(1L)).thenReturn(100L);
        when(stockCacheService.getStock(2L)).thenReturn(50L);
        when(stockCacheService.getStock(3L)).thenReturn(0L);

        StockReconciler.ReconcileResult r = reconciler.reconcileOnce();

        assertThat(r.checked()).isEqualTo(3);
        assertThat(r.consistent()).isEqualTo(3);
        assertThat(r.redisHigher()).isZero();
        assertThat(r.redisLower()).isZero();
        assertThat(r.notPreheated()).isZero();
        assertThat(r.fixed()).isZero();
        verify(stockCacheService, never()).preheat(anyLong());
    }

    // ---------- 用例 2：偏高必须修 ----------

    @Test
    @DisplayName("用例2 Redis 偏高：计数正确，并且真的调 preheat 把名额收回来")
    void redisHigher_isFixed() {
        when(courseStockMapper.selectList(null)).thenReturn(List.of(row(88, 100, 0)));
        // Redis 105 > 数据库 100：多出 5 个虚报名额，会超卖
        when(stockCacheService.getStock(88L)).thenReturn(105L);

        StockReconciler.ReconcileResult r = reconciler.reconcileOnce();

        assertThat(r.redisHigher()).isEqualTo(1);
        assertThat(r.fixed()).isEqualTo(1);
        assertThat(r.consistent()).isZero();
        // 🔴 这条断言防的是"只打日志不修"：对账发现不一致却不动手，等于没做
        verify(stockCacheService, times(1)).preheat(88L);
    }

    // ---------- 用例 3：偏低绝不修（🔴 最值钱的一条）----------

    @Test
    @DisplayName("用例3 🔴 Redis 偏低：只计数，绝不调 preheat（修了会变成超卖）")
    void redisLower_isNeverFixed() {
        when(courseStockMapper.selectList(null)).thenReturn(List.of(row(88, 100, 0)));
        // Redis 95 < 数据库 100：可能只是某个请求刚扣了 Redis 还没提交
        when(stockCacheService.getStock(88L)).thenReturn(95L);

        StockReconciler.ReconcileResult r = reconciler.reconcileOnce();

        assertThat(r.redisLower()).isEqualTo(1);
        assertThat(r.fixed()).isZero();
        assertThat(r.redisHigher()).isZero();
        // 🔴 核心：修偏低 = 把"少卖 5 个"变成"多卖 5 个"，是造错不是纠错
        verify(stockCacheService, never()).preheat(anyLong());
    }

    // ---------- 用例 4：未预热 ----------

    @Test
    @DisplayName("用例4 Redis 未预热（getStock 返回 null）：计入 notPreheated，不修也不报错")
    void notPreheated_isCountedOnly() {
        when(courseStockMapper.selectList(null)).thenReturn(List.of(row(77, 100, 0)));
        when(stockCacheService.getStock(77L)).thenReturn(null);

        StockReconciler.ReconcileResult r = reconciler.reconcileOnce();

        assertThat(r.notPreheated()).isEqualTo(1);
        assertThat(r.consistent()).isZero();
        assertThat(r.redisHigher()).isZero();
        assertThat(r.redisLower()).isZero();
        assertThat(r.fixed()).isZero();
        verify(stockCacheService, never()).preheat(anyLong());
    }

    // ---------- 用例 5：混合 ----------

    @Test
    @DisplayName("用例5 混合场景：一致/偏高/偏低/未预热 各 1 门，只有偏高的那门被修")
    void mixed_countsEachCategory() {
        when(courseStockMapper.selectList(null))
                .thenReturn(List.of(row(1, 100, 0), row(2, 100, 0), row(3, 100, 0), row(4, 100, 0)));
        when(stockCacheService.getStock(1L)).thenReturn(100L); // 一致
        when(stockCacheService.getStock(2L)).thenReturn(120L); // 偏高
        when(stockCacheService.getStock(3L)).thenReturn(80L);  // 偏低
        when(stockCacheService.getStock(4L)).thenReturn(null); // 未预热

        StockReconciler.ReconcileResult r = reconciler.reconcileOnce();

        assertThat(r.checked()).isEqualTo(4);
        assertThat(r.consistent()).isEqualTo(1);
        assertThat(r.redisHigher()).isEqualTo(1);
        assertThat(r.redisLower()).isEqualTo(1);
        assertThat(r.notPreheated()).isEqualTo(1);
        assertThat(r.fixed()).isEqualTo(1);

        // 只有 2 号被修，其余三门一个都不能碰
        verify(stockCacheService, times(1)).preheat(2L);
        verify(stockCacheService, never()).preheat(1L);
        verify(stockCacheService, never()).preheat(3L);
        verify(stockCacheService, never()).preheat(4L);
    }

    // ---------- 用例 6：单轮修正上限 ----------

    @Test
    @DisplayName("用例6 偏高门数超过上限：redisHigher=3 但只修 2 门，剩下的留给下一轮")
    void higherExceedsMaxFix_capsAtLimit() {
        StockReconciler limited = newReconciler(2);
        when(courseStockMapper.selectList(null))
                .thenReturn(List.of(row(1, 100, 0), row(2, 100, 0), row(3, 100, 0)));
        when(stockCacheService.getStock(1L)).thenReturn(150L);
        when(stockCacheService.getStock(2L)).thenReturn(150L);
        when(stockCacheService.getStock(3L)).thenReturn(150L);

        StockReconciler.ReconcileResult r = limited.reconcileOnce();

        // 三门的"偏高"事实都要数出来，但只修 2 门
        assertThat(r.redisHigher()).isEqualTo(3);
        assertThat(r.fixed()).isEqualTo(2);
        // 为什么要有上限：Redis 整体被清空后 100 门课同时偏高，
        // 一轮修 100 个 key 会把 Redis 打满，宁可分几轮慢慢修
        verify(stockCacheService, times(2)).preheat(anyLong());
    }

    // ---------- 用例 7：某门课读 Redis 抛异常 ----------

    @Test
    @DisplayName("用例7 某门课 getStock 抛异常：整轮抛出，由 reconcile() 的 try-catch 兜住")
    void getStockThrows_propagates() {
        when(courseStockMapper.selectList(null)).thenReturn(List.of(row(1, 100, 0), row(2, 100, 0)));
        when(stockCacheService.getStock(1L)).thenThrow(new RuntimeException("Redis 连接被拒绝"));

        // 设计如此：Redis 整体挂掉应该告警一次，而不是逐门吞掉异常、日志刷 100 遍
        assertThatThrownBy(() -> reconciler.reconcileOnce())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redis 连接被拒绝");
    }

    @Test
    @DisplayName("用例7b reconcile() 吞掉异常：调度器不会因一轮失败而停摆")
    void reconcile_swallowsException() {
        when(courseStockMapper.selectList(null)).thenThrow(new RuntimeException("MySQL 挂了"));

        assertThatCode(() -> reconciler.reconcile()).doesNotThrowAnyException();
    }
}
