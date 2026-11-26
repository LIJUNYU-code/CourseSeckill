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
     * 查出「创建超过 deadline 但仍是待处理」的订单。
     *
     * <p>为什么要 LIMIT：清扫是一次批量操作，如果积压了几十万条 PENDING，
     * 一次性全部捞出来会占满内存、长时间锁住数据库。分批处理，下一轮再捞剩下的。
     */
    @Select("SELECT * FROM seckill_order WHERE status = 0 AND create_time < #{deadline} "
            + "ORDER BY id LIMIT #{limit}")
    List<SeckillOrder> selectStalePending(@Param("deadline") LocalDateTime deadline,
                                          @Param("limit") int limit);

}
