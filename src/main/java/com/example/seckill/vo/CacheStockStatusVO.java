package com.example.seckill.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 库存对账视图：Redis 与数据库两边的库存并排放出来。
 * <p>
 * 为什么要有它：不一致的时候，只看一个数字永远不知道该信谁。
 * 这个对象让"Redis 说 40、DB 说 38"这种事实自己跳出来。
 */
@Schema(description = "库存对账结果")
public record CacheStockStatusVO(

        @Schema(description = "课程 ID", example = "88")
        Long courseId,

        @Schema(description = "Redis 里的库存；null 表示未预热", example = "40")
        Long redisStock,

        @Schema(description = "数据库里的库存", example = "40")
        Integer dbStock,

        @Schema(description = "数据库里已售数量", example = "60")
        Integer dbSold,

        @Schema(description = "总名额", example = "100")
        Integer capacity,

        @Schema(description = "redisStock == dbStock 时为 true", example = "true")
        boolean consistent
) {
}
