package com.example.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("seckill_order")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeckillOrder {

    public static final int STATUS_PENDING   = 0;

    public static final int STATUS_SUCCESS   = 1;

    public static final int STATUS_FAILED    = 2;

    public static final int STATUS_CANCELLED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long studentId;

    private Long courseId;

    private Integer status;

    private String failReason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;


}
