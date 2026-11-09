package com.example.seckill.config;

import com.example.seckill.common.Result;
import com.example.seckill.common.ResultCode;
import com.example.seckill.security.JwtAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter, ObjectMapper objectMapper) throws Exception {
        http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))       //  不存 session
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .exceptionHandling(e ->e.authenticationEntryPoint((req, res, ex) -> {
                            res.setStatus(401);
                            res.setContentType("application/json;charset=UTF-8");
                            objectMapper.writeValue(res.getWriter(),
                                    Result.error(ResultCode.UNAUTHORIZED, "未登录或登录已过期"));
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            res.setStatus(403);
                            res.setContentType("application/json;charset=UTF-8");
                            objectMapper.writeValue(res.getWriter(),
                                    Result.error(ResultCode.FORBIDDEN, "无权访问"));
                        }))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();

    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> disableAutoRegistration(JwtAuthenticationFilter f) {
        var reg = new FilterRegistrationBean<>(f);
        reg.setEnabled(false);
        return reg;
    }
}
