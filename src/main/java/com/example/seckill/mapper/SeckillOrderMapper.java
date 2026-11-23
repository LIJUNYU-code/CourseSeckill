package com.example.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckill.entity.SeckillOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;


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

}
