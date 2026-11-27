package com.example.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckill.entity.SeckillOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;


public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /**
     * 条件改状态：只有当前状态等于 expectStatus 时才改。
     * 和 CourseStockMapper.tryDecrement 是同一个思路——affected rows 才是可信的答案。
     */
    @Update("UPDATE seckill_order SET status = #{newStatus} " +
            "WHERE id = #{id} AND status = #{expectStatus}")
    int updateStatusIfMatch(@Param("id") Long id,
                            @Param("newStatus") Integer newStatus,
                            @Param("expectStatus") Integer expectStatus);

    /**
     * 改状态：只有当前 status=0（待处理）时才改。
     * 返回 0 说明这条订单不是待处理状态（可能已被别的消费者处理过，或已退课）。
     */
    @Update("UPDATE seckill_order SET status = #{newStatus} " +
            "WHERE order_no = #{orderNo} AND status = 0")
    int updateStatusByOrderNo(@Param("orderNo") String orderNo,
                              @Param("newStatus") Integer newStatus);

    /**
     * 改成失败并记录原因。
     */
    @Update("UPDATE seckill_order SET status = #{newStatus}, fail_reason = #{failReason} " +
            "WHERE order_no = #{orderNo} AND status = 0")
    int updateStatusAndFailReason(@Param("orderNo") String orderNo,
                                  @Param("newStatus") Integer newStatus,
                                  @Param("failReason") String failReason);

    /**
     * 查出「创建超过 deadline、仍是待处理、且从没被任何人动过」的订单。
     *
     * <p>为什么要 LIMIT：清扫是一次批量操作，如果积压了几十万条 PENDING，
     * 一次性全部捞出来会占满内存、长时间锁住数据库。分批处理，下一轮再捞剩下的。
     *
     * <p><b>为什么要 {@code update_time = create_time} 这个条件</b>：
     * 它在排除「消费者正在处理 / 已经处理过」的订单。
     *
     * <p>没有它时的坏时序：清扫判定订单超时 → 条件更新成功 → 归还 Redis；
     * 同时消费者也在处理这条消息，把订单改成成功、扣了 MySQL 库存。
     * 两边都以为自己赢了，结果 Redis 多还一个名额（偏高）。对账几分钟后能修回来，
     * 但那几分钟里多出来的名额是真的能被抢走的。
     *
     * <p>{@code update_time} 带 {@code ON UPDATE CURRENT_TIMESTAMP}：只要消费者动过这条订单
     * （状态从 0 变成 1/2），{@code update_time} 就会晚于 {@code create_time}，
     * 于是它不会再被清扫选中。反过来 {@code update_time = create_time} 精确表达
     * 「插进去之后没人碰过」，正是清扫该处理的集合。
     *
     * <p>这只是「缩小竞争窗口」，不是「消除竞争」——两个操作之间仍有微秒级间隙。
     * 真正的兜底是库存对账（{@code StockReconciler}），它会把偏高的 Redis 修回来。
     *
     * <p>⚠️ 拼字符串时别把空格吃掉：{@code "... < #{deadline}"} 结尾没有空格的话，
     * 下一段拼上来会变成 {@code create_timeORDER BY id}，直接语法错误。
     */
    @Select("SELECT * FROM seckill_order WHERE status = 0 AND create_time < #{deadline} "
            + "AND update_time = create_time "
            + "ORDER BY id LIMIT #{limit}")
    List<SeckillOrder> selectStalePending(@Param("deadline") LocalDateTime deadline,
                                          @Param("limit") int limit);

}
