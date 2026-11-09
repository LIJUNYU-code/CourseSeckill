package com.example.seckill.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI seckillOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CourseSeckill 高并发选课引擎 API")
                        .version("1.0")
                        .description("阶段一 骨架版本、登录获取JWT后，在请求头携带Authorization: " +
                                "Bearer <Token>"))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("把登录接口拿到的 token 粘进来，不要加 Bearer 前缀")))
                // ③ 声明"默认所有接口都需要这个认证"
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }
}





