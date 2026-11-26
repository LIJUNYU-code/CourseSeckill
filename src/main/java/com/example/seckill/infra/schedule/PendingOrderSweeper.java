package com.example.seckill.infra.schedule;

import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.infra.cache.StockCacheService;
import com.example.seckill.mapper.SeckillOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PENDING 订单超时清扫。
 *
 * <p>解决什么问题：订单创建后要等 MQ 消费者把它改成「成功」或「失败」。
 * 如果消费者停摆（RabbitMQ 挂了、@RabbitListener 启动失败、队列被误删），
 * 消息会一直躺在队列里，订单永久停在 status=0，用户看到的是永远「待处理」。
 *
 * <p>这不是 MQ 的 bug —— MQ 只保证「消息不丢」，不保证「一定被消费」。
 * 兜底要靠业务自己：谁创建了中间状态，谁负责给它一个出口。
 */
@Slf4j
@Component
public class PendingOrderSweeper {

    private final SeckillOrderMapper seckillOrderMapper;
    private final StockCacheService stockCacheService;
    private final long pendingTimeoutMinutes;
    private final int batchSize;

    /**
     * 用构造器注入 + @Value 而不是 @RequiredArgsConstructor + 字段 @Value。
     *
     * <p>理由：字段注入的 @Value 无法在单测里替换，只能起 Spring 容器测；
     * 构造器参数可以，于是纯 Mockito 测试能直接 new 出来。
     * 这个取舍和项目里其它类（JwtUtil 用字段 @Value）不一致，是刻意的：
     * 这个类要单测，JwtUtil 的配置值不影响逻辑。
     */
    public PendingOrderSweeper(
            SeckillOrderMapper seckillOrderMapper,
            StockCacheService stockCacheService,
            @Value("${seckill.pending-timeout-minutes:5}") long pendingTimeoutMinutes,
            @Value("${seckill.pending-sweep-batch:200}") int batchSize) {
        this.seckillOrderMapper = seckillOrderMapper;
        this.stockCacheService = stockCacheService;
        this.pendingTimeoutMinutes = pendingTimeoutMinutes;
        this.batchSize = batchSize;
    }

    /**
     * 定时入口。参数全部走配置，方便压测时调快（改成 5 秒能立刻看到效果）。
     *
     * <p>initialDelay 给 30 秒：应用刚启动时 MQ 消费者可能还没就绪，
     * 立刻清扫会把本来正常在途的订单误判为超时。
     */
    @Scheduled(
            fixedDelayString = "${seckill.pending-sweep-interval-ms:60000}",
            initialDelayString = "${seckill.pending-sweep-initial-delay-ms:30000}")
    public void sweep() {
        try {
            sweepOnce(LocalDateTime.now());
        } catch (Exception e) {
            // 定时任务里绝不能把异常抛出去：一次失败不该让整个调度停掉
            log.error("PENDING 订单清扫失败", e);
        }
    }

    /**
     * 可单测的实际逻辑（把时间当参数传进来，测试里不用等 5 分钟）。
     *
     * <p>🔴 关键：这里<b>只还 Redis，不碰 MySQL</b>。
     * 理由见下面 {@link #sweepOnce} 方法体内的注释——写错这一处会凭空多出名额。
     *
     * @return 本轮清扫掉的订单数
     */
    public int sweepOnce(LocalDateTime now) {
        LocalDateTime deadline = now.minusMinutes(pendingTimeoutMinutes);
        List<SeckillOrder> stale = seckillOrderMapper.selectStalePending(deadline, batchSize);
        if (stale.isEmpty()) {
            return 0;
        }
        log.info("发现 {} 条超时未处理订单，开始清扫", stale.size());

        int swept = 0;
        for (SeckillOrder order : stale) {
            // 条件更新：只有仍是 status=0 才改。
            // affected=0 说明消费者已经处理了这条单，绝不能覆盖它的结果
            int affected = seckillOrderMapper.updateStatusAndFailReason(
                    order.getOrderNo(), SeckillOrder.STATUS_FAILED, "处理超时");
            if (affected == 0) {
                continue;
            }

            // ===== 只还 Redis，不动 MySQL =====
            //
            // 为什么不能调 courseStockMapper.returnStock()：
            //   v3 的库存扣减发生在【消费者】里（SeckillOrderConsumer.handle 的 tryDecrement），
            //   抢课接口本身只做两件事：Redis 门禁扣 1、插一条 PENDING 订单，MySQL 一行没碰。
            //   而清扫的前提恰恰是「消费者没跑」→ MySQL 从来没被扣过 → 再还一次就是凭空造名额。
            //
            //   反例：课程 88 已有 50 个成功订单（stock=50, sold=50），清扫一条卡住的 PENDING 后
            //   变成 stock=51, sold=49，总和仍是 100（账目守恒看不出来），但这门课能卖出 101 单。
            //
            // 那「消费者扣了 MySQL 却卡住」的情况怎么办？
            //   不会发生。消费者的 tryDecrement 与 updateStatusByOrderNo 在【同一个事务】里
            //   （handle 用 transactionTemplate.executeWithoutResult 包住全部步骤），
            //   要么一起提交（订单变 SUCCESS），要么一起回滚（MySQL 没扣）。
            //   所以「MySQL 已扣 + 订单仍 PENDING」这个组合在 v3 里不可能出现。
            //
            // 那消费者晚到的情况呢？
            //   消费者处理时会发现 updateStatusByOrderNo 返回 0（订单已被清扫改成 FAILED），
            //   它自己会调 returnStock 把 MySQL 平掉。账由消费者平，清扫不插手。
            try {
                stockCacheService.incrementIfPresent(order.getCourseId());
            } catch (Exception e) {
                // Redis 偏高是安全方向（只浪费名额，不会超卖），不抛。
                // 交给对账脚本 loadtest/reconcile.ps1 修。
                log.error("清扫归还 Redis 失败（Redis 偏高，只浪费名额）courseId={}",
                        order.getCourseId(), e);
            }

            swept++;
            log.warn("订单超时未处理，已标记失败 orderNo={} courseId={} studentId={}",
                    order.getOrderNo(), order.getCourseId(), order.getStudentId());
        }
        return swept;
    }
}
