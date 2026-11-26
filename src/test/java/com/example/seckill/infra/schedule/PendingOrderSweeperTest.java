package com.example.seckill.infra.schedule;

import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.infra.cache.StockCacheService;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.support.TableInfoTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PendingOrderSweeper 单元测试。
 *
 * <p>覆盖阶段 7 任务③：把卡在 status=0 的订单标记失败并归还名额。
 *
 * <p>🔴 本类最值钱的是 {@code casLost_doesNotReturnRedis} 那条：
 * 它防的是「清扫把消费者已经处理成功的订单改成失败」——
 * 一旦发生，用户明明选上了却被告知失败，而且库存被多还一份。
 *
 * <p>另一条同样重要：{@code neverTouchesMySqlStock}。
 * 清扫只还 Redis、绝不碰 MySQL 库存，因为 v3 里 MySQL 是在消费者事务内扣的，
 * 「订单仍 PENDING」意味着消费者没跑，MySQL 从来没被扣过。
 */
@ExtendWith(MockitoExtension.class)
class PendingOrderSweeperTest {

    @Mock
    private SeckillOrderMapper seckillOrderMapper;

    @Mock
    private StockCacheService stockCacheService;

    /** 超时 5 分钟、每轮 200 条 —— 与 application.yaml 的默认值一致。 */
    private PendingOrderSweeper newSweeper(long timeoutMinutes, int batchSize) {
        return new PendingOrderSweeper(seckillOrderMapper, stockCacheService, timeoutMinutes, batchSize);
    }

    @BeforeAll
    static void initTableInfo() {
        TableInfoTestSupport.initAll();
    }

    private SeckillOrder staleOrder(String orderNo, Long courseId) {
        return SeckillOrder.builder()
                .id(1L)
                .orderNo(orderNo)
                .studentId(100L)
                .courseId(courseId)
                .status(SeckillOrder.STATUS_PENDING)
                .createTime(LocalDateTime.of(2026, 1, 1, 11, 0))
                .build();
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("没有超时订单：返回 0，不碰 Redis")
    void noStaleOrders_returnsZero() {
        when(seckillOrderMapper.selectStalePending(any(), anyInt())).thenReturn(List.of());

        int swept = newSweeper(5, 200).sweepOnce(LocalDateTime.of(2026, 1, 1, 12, 0));

        assertThat(swept).isZero();
        verify(stockCacheService, never()).incrementIfPresent(any());
        verify(seckillOrderMapper, never()).updateStatusAndFailReason(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("1 条超时订单：CAS 成功 → 标记失败 + 归还 Redis")
    void oneStaleOrder_marksFailedAndReturnsRedis() {
        when(seckillOrderMapper.selectStalePending(any(), anyInt()))
                .thenReturn(List.of(staleOrder("O1", 88L)));
        when(seckillOrderMapper.updateStatusAndFailReason(
                eq("O1"), eq(SeckillOrder.STATUS_FAILED), eq("处理超时"))).thenReturn(1);

        int swept = newSweeper(5, 200).sweepOnce(LocalDateTime.of(2026, 1, 1, 12, 0));

        assertThat(swept).isEqualTo(1);
        verify(stockCacheService).incrementIfPresent(88L);
    }

    @Test
    @DisplayName("🔴 CAS 返回 0（消费者抢先处理了）：绝不归还 Redis，不覆盖成功订单")
    void casLost_doesNotReturnRedis() {
        when(seckillOrderMapper.selectStalePending(any(), anyInt()))
                .thenReturn(List.of(staleOrder("O1", 88L)));
        // 条件更新匹配不上：消费者已经把 status 从 0 改成 1 了
        when(seckillOrderMapper.updateStatusAndFailReason(anyString(), anyInt(), anyString()))
                .thenReturn(0);

        int swept = newSweeper(5, 200).sweepOnce(LocalDateTime.of(2026, 1, 1, 12, 0));

        assertThat(swept).isZero();
        // 🔴 这条断言防的是灾难：订单已经成功，不能再还 Redis（否则名额凭空多一个）
        verify(stockCacheService, never()).incrementIfPresent(any());
    }

    @Test
    @DisplayName("🔴 清扫全程不碰 MySQL 库存表（v3 里 MySQL 由消费者扣，没扣过就不能还）")
    void neverTouchesMySqlStock() {
        when(seckillOrderMapper.selectStalePending(any(), anyInt()))
                .thenReturn(List.of(staleOrder("O1", 88L)));
        when(seckillOrderMapper.updateStatusAndFailReason(anyString(), anyInt(), anyString()))
                .thenReturn(1);

        newSweeper(5, 200).sweepOnce(LocalDateTime.of(2026, 1, 1, 12, 0));

        // PendingOrderSweeper 的构造器里根本没有 CourseStockMapper —— 编译期就保证了。
        // 这条用例的存在意义是：把「为什么没有」写进测试，防止将来有人"顺手补上"。
        // 详见 PendingOrderSweeper#sweepOnce 方法体内的注释。
        verify(stockCacheService).incrementIfPresent(88L);
    }

    @Test
    @DisplayName("Redis 归还抛异常：不向外抛，MySQL 侧已标记成功")
    void redisFailure_isSwallowed() {
        when(seckillOrderMapper.selectStalePending(any(), anyInt()))
                .thenReturn(List.of(staleOrder("O1", 88L)));
        when(seckillOrderMapper.updateStatusAndFailReason(anyString(), anyInt(), anyString()))
                .thenReturn(1);
        org.mockito.Mockito.doThrow(new RuntimeException("Redis 挂了"))
                .when(stockCacheService).incrementIfPresent(88L);

        // 不抛异常
        int swept = newSweeper(5, 200).sweepOnce(LocalDateTime.of(2026, 1, 1, 12, 0));

        assertThat(swept).isEqualTo(1);
    }

    @Test
    @DisplayName("batchSize 被原样传给查询")
    void batchSizeIsPassedThrough() {
        when(seckillOrderMapper.selectStalePending(any(), anyInt())).thenReturn(List.of());

        newSweeper(5, 2).sweepOnce(LocalDateTime.of(2026, 1, 1, 12, 0));

        verify(seckillOrderMapper).selectStalePending(any(), eq(2));
    }

    @Test
    @DisplayName("deadline 计算：now=12:00、超时 5 分钟 → deadline=11:55")
    void deadlineIsNowMinusTimeout() {
        when(seckillOrderMapper.selectStalePending(any(), anyInt())).thenReturn(List.of());

        newSweeper(5, 200).sweepOnce(LocalDateTime.of(2026, 1, 1, 12, 0));

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(seckillOrderMapper).selectStalePending(captor.capture(), anyInt());
        assertThat(captor.getValue()).isEqualTo(LocalDateTime.of(2026, 1, 1, 11, 55));
    }

    @Test
    @DisplayName("多条订单：只处理 CAS 成功的，失败的那条跳过")
    void mixedResults_onlySweepsWinners() {
        when(seckillOrderMapper.selectStalePending(any(), anyInt()))
                .thenReturn(List.of(staleOrder("O1", 88L), staleOrder("O2", 88L), staleOrder("O3", 77L)));
        when(seckillOrderMapper.updateStatusAndFailReason(eq("O1"), anyInt(), anyString())).thenReturn(1);
        when(seckillOrderMapper.updateStatusAndFailReason(eq("O2"), anyInt(), anyString())).thenReturn(0);
        when(seckillOrderMapper.updateStatusAndFailReason(eq("O3"), anyInt(), anyString())).thenReturn(1);

        int swept = newSweeper(5, 200).sweepOnce(LocalDateTime.of(2026, 1, 1, 12, 0));

        assertThat(swept).isEqualTo(2);
        verify(stockCacheService, times(1)).incrementIfPresent(88L);
        verify(stockCacheService, times(1)).incrementIfPresent(77L);
    }

    @Test
    @DisplayName("查询本身抛异常：sweep() 吞掉，不向外抛（定时任务不能被一次失败打死）")
    void sweep_swallowsException() {
        when(seckillOrderMapper.selectStalePending(any(), anyInt()))
                .thenThrow(new RuntimeException("数据库连不上"));

        // sweep() 是定时入口，内部 try-catch
        newSweeper(5, 200).sweep();

        // 没有抛异常 = 通过
    }
}
