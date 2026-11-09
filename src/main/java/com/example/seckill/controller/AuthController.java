package com.example.seckill.controller;

import com.example.seckill.common.Result;
import com.example.seckill.dto.LoginRequestDTO;
import com.example.seckill.dto.LoginResponseDTO;
import com.example.seckill.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@AllArgsConstructor
@RequestMapping("/api/auth")
@Tag(name = "账号认证", description = "登录获取 JWT 令牌")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "登录", description = "支持管理员用户名或学生学号；返回 JWT，有效期 2 小时")
    @SecurityRequirements   // 登录接口本身不需要 token，否则死锁
    public Result<LoginResponseDTO> login(@Valid @RequestBody LoginRequestDTO loginRequestDTO) {
        return Result.success(authService.login(loginRequestDTO));
    }
}