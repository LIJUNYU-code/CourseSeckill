package com.example.seckill.security;

import com.example.seckill.common.Result;
import com.example.seckill.common.ResultCode;
import com.example.seckill.infra.cache.RateLimitResult;
import com.example.seckill.infra.cache.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String GLOBAL_KEY = "seckill:rate:global";
    private static final String STUDENT_KEY_PREFIX = "seckill:rate:student:";


    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    private final boolean enabled;
    private final int globalCapacity;
    private final double globalRefill;
    private final int studentCapacity;
    private final double studentRefill;

    public RateLimitFilter(RateLimitService rateLimitService,
                           ObjectMapper objectMapper,
                           @Value("${seckill.rate-limit-enabled:true}") boolean enabled,
                           @Value("${seckill.rate-limit-global-capacity:2000}") int globalCapacity,
                           @Value("${seckill.rate-limit-global-refill-per-second:1500}") double globalRefill,
                           @Value("${seckill.rate-limit-student-capacity:5}") int studentCapacity,
                           @Value("${seckill.rate-limit-student-refill-per-second:1}") double studentRefill) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.globalCapacity = globalCapacity;
        this.globalRefill = globalRefill;
        this.studentCapacity = studentCapacity;
        this.studentRefill = studentRefill;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        // 只拦抢课本身：POST /api/seckill/{courseId}
        // 用精确匹配而不是 startsWith("/api/seckill")，否则 cancel 也会被限流
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/seckill/") || uri.endsWith("/cancel");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 第一层：全局桶
        RateLimitResult global = rateLimitService.tryAcquire(
                GLOBAL_KEY, globalCapacity, globalRefill, 1);
        if (global.degraded()) {
            log.error("全局限流降级，本次请求放行（限流未生效） uri={}", request.getRequestURI());
        }
        if (!global.allowed()) {
            reject(response, global, "系统繁忙，请稍后重试");
            return;
        }

        // 第二层：每学生桶（匿名请求跳过 —— 它已经被全局桶挡住了）
        Long studentId = currentStudentId();
        if (studentId != null) {
            RateLimitResult student = rateLimitService.tryAcquire(
                    STUDENT_KEY_PREFIX + studentId, studentCapacity, studentRefill, 1);
            if (!student.allowed()) {
                // 注意：这里已经消耗了一个全局令牌。这是有意的 ——
                // 被学生桶拒掉的请求确实占用了系统资源（解析 JWT、查 Redis），
                // 不退还才符合"限流保护系统"的初衷。
                reject(response, student, "操作过于频繁，请稍后再试");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private Long currentStudentId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            return null;
        }
        boolean isStudent = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_STUDENT".equals(a.getAuthority()));
        return isStudent ? id : null;
    }

    private void reject(HttpServletResponse response, RateLimitResult result, String message)
            throws IOException {
        long retryAfterSeconds = Math.max(1L, (result.retryAfterMs() + 999L) / 1000L);
        response.setStatus(ResultCode.TOO_MANY_REQUESTS);
        response.setContentType("application/json;charset=UTF-8");
        // RFC 9110：Retry-After 用秒（整数）
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0L, result.remaining())));
        objectMapper.writeValue(response.getWriter(),
                Result.error(ResultCode.TOO_MANY_REQUESTS, message));
        log.warn("限流拒绝 uri={} retryAfter={}s remaining={}",
                message, retryAfterSeconds, result.remaining());
    }


}
