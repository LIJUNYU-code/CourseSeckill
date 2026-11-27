package com.example.seckill.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("mq_dead_letter")
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class MqDeadLetter {

    @TableId(type = IdType.AUTO)
    private long id;

    private String msgId;
    private String orderNo;
    private Long studentId;
    private Long courseId;
    private String reason;
    private String body;
    private LocalDateTime createTime;
}
