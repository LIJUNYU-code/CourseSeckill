package com.example.seckill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "登录响应")
public class LoginResponseDTO {

    @Schema(description = "JWT 令牌，后续请求放在请求头 Authorization: Bearer <token>",
            example = "eyJhbGciOiJIUzI1NiJ9...")
    private String token;

    @Schema(description = "登录用户名/学号", example = "S000000001")
    private String username;

    @Schema(description = "角色 ADMIN / STUDENT，仅用于前端界面显示，无安全作用",
            allowableValues = {"ADMIN", "STUDENT"})
    private String role;

    @Schema(description = "真实姓名", example = "学生1")
    private String realName;
}