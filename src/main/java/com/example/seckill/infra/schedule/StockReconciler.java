package com.example.seckill.infra.schedule;


import com.example.seckill.entity.CourseStock;
import com.example.seckill.infra.cache.StockCacheService;
import com.example.seckill.mapper.CourseStockMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "seckill.reconcile-enable", havingValue = "true", matchIfMissing = true)
public class StockReconciler {

    private final CourseStockMapper courseStockMapper;
    private final StockCacheService stockCacheService;

    /** 单轮最多修几门课。Redis 整体被清空时不该一次改 100 个 key 把 Redis 打满。 */
    private final int maxFixPerRound;

    public StockReconciler(CourseStockMapper courseStockMapper,
                           StockCacheService stockCacheService,
                           @Value("${seckill.reconcile-max-fix-per-round:20}") int maxFixPerRound) {
        this.courseStockMapper = courseStockMapper;
        this.stockCacheService = stockCacheService;
        this.maxFixPerRound = maxFixPerRound;
    }

    @Scheduled(fixedDelayString = "${seckill.reconcile-interval-ms:300000}",
            initialDelayString = "${seckill.reconcile-initial-delay-ms:60000}")
    public void reconcile() {
        try{
            ReconcileResult r = reconcileOnce();
            if(r.redisHigher() > 0 || r.redisLower() > 0){
                log.warn("库存对账发现不一致：{}", r);
            }else{
                log.info("库存对账通过: {}", r);
            }
        }catch (Exception e){
            // 定时任务的异常必须自己吞掉：@Scheduled 的异常只会打日志，
            // 但会打断这一轮，下一轮照常跑。这里显式 catch 是为了日志更清楚。
            log.error("库存对账任务异常", e);
        }
    }

    /**
     * 执行一轮对账。抽成 public 方法是为了能在单测里直接调，不依赖调度器。
     *
     * @return 本轮统计
     */
    public ReconcileResult reconcileOnce() {
        List<CourseStock> rows = courseStockMapper.selectList(null);
        int consistent = 0;
        int higher = 0;
        int lower = 0;
        int notPreheated = 0;
        int fixed = 0;

        for ( CourseStock row : rows){
            Long redisStock = stockCacheService.getStock(row.getCourseId());
            if(redisStock == null){
                notPreheated++;
                continue;
            }
            long dbStock = row.getStock().longValue();
            if(redisStock == dbStock){
                consistent++;
            }else if(redisStock > dbStock){
                higher++;
                if (fixed < maxFixPerRound) {
                    log.warn("Redis 偏高，收回虚报名额 courseId={} redis={} db={}", row.getCourseId(), redisStock, dbStock);
                    // preheat 是"用 MySQL 的 stock 覆盖 Redis"，正是收回虚报名额要做的动作。
                    // 不调这一行，对账就只是"发现不一致"而不会修，功能等于没做。
                    stockCacheService.preheat(row.getCourseId());
                    fixed++;
                }else {
                    log.warn("Redis 偏高但已达单轮修正上限，留给下一轮 courseId={}", row.getCourseId());
                }
            }else{
                // 偏低只告警不修：可能是在途请求，修了会变成偏高。
                lower++;
                log.warn("Redis 偏低（安全方向，不自动修）courseId={} redis={} db={}",
                        row.getCourseId(), redisStock, dbStock);
            }
        }
        return new ReconcileResult(rows.size(), consistent, higher, lower, notPreheated, fixed);
    }

    /** 一轮对账的统计结果。record 让日志一行能打全。 */
    public record ReconcileResult(int checked, int consistent, int redisHigher,
                                  int redisLower, int notPreheated, int fixed) {
    }

}
