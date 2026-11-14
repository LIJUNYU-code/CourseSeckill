package com.example.seckill.controller;

import com.example.seckill.common.Result;
import com.example.seckill.common.ResultCode;
import com.example.seckill.entity.CourseStock;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.infra.cache.StockCacheService;
import com.example.seckill.mapper.CourseStockMapper;
import com.example.seckill.vo.CacheStockStatusVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 缓存运维接口。
 * <p>
 * 这三个不是业务接口，是为了让"Redis 和数据库不一致"这件事能被观测到。
 * 没有它们，对账只能靠人肉敲 memurai-cli，压测时来不及。
 * <p>
 * 🔴 生产环境应当关闭（配置里加开关或整类摘掉），本项目保留因为压测要用。
 */
@RestController
@RequestMapping("/api/admin/cache/stock")
@AllArgsConstructor
@Tag(name = "缓存运维", description = "库存预热与对账，仅管理员可用")
public class CacheAdminController {

    private final StockCacheService stockCacheService;
    private final CourseStockMapper courseStockMapper;

    @PostMapping("/{courseId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "预热单个课程", description = "从数据库读 stock 覆盖写入 Redis")
    public Result<Void> preheat(@PathVariable Long courseId) {
        stockCacheService.preheat(courseId);
        return Result.success();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "预热全部课程", description = "压测前复位、数据修正时用")
    public Result<Void> preheatAll() {
        stockCacheService.preheatAll();
        return Result.success();
    }

    @GetMapping("/{courseId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "库存对账", description = "并排返回 Redis 与数据库的库存，供压测脚本断言")
    public Result<CacheStockStatusVO> status(@PathVariable Long courseId) {
        CourseStock db = courseStockMapper.selectByCourseId(courseId);
        if (db == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程库存不存在");
        }
        Long redisStock = stockCacheService.getStock(courseId);
        // redisStock 为 null 时 consistent 必须是 false，
        // 否则"未预热"会被当成"一致"，这正是最难查的那种 bug。
        boolean consistent = redisStock != null && redisStock == db.getStock().longValue();
        return Result.success(new CacheStockStatusVO(
                courseId,
                redisStock,
                db.getStock(),
                db.getSold(),
                db.getCapacity(),
                consistent
        ));
    }
}
