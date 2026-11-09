package com.example.seckill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "登录请求")
public class LoginRequestDTO {

    @NotBlank(message = "账号不能为空")
    @Schema(description = "账号：管理员填用户名（如 admin），学生填学号（如 S000000001）",
            example = "S000000001", requiredMode = Schema.RequiredMode.REQUIRED)
    private String account;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "密码", example = "Abc12345", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}