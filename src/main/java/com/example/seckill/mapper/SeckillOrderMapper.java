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

}
