package com.example.seckill.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@TableName("student")
public class Student {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String studentNo;

    private String name;

    @JsonIgnore
    private String password;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;


}
