package com.example.seckill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "课程详情（含库存）")
public class CourseDetailResponse {
    @Schema(description = "课程 id", example = "1")
    private Long courseId;

    @Schema(description = "课程名称", example = "课程1")
    private String courseName;

    @Schema(description = "授课教师", example = "教师1")
    private String teacher;

    @Schema(description = "学分", example = "2.5")
    private BigDecimal credit;

    @Schema(description = "选课开始时间")
    private LocalDateTime startTime;

    @Schema(description = "选课结束时间")
    private LocalDateTime endTime;

    @Schema(description = "总名额", example = "100")
    private Integer capacity;

    @Schema(description = "剩余名额", example = "100")
    private Integer stock;

    @Schema(description = "已选人数", example = "0")
    private Integer sold;

    @Schema(description = "当前学生对该课的订单状态；null 表示没选过", example = "1")
    private Integer myOrderStatus;

}