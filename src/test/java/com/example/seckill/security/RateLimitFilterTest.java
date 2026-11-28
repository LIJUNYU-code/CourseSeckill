package com.example.seckill.security;

import com.example.seckill.infra.cache.RateLimitResult;
import com.example.seckill.infra.cache.RateLimitService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimitFilter 单元测试 —— 覆盖"哪些请求该限流、限流之后响应长什么样"。
 *
 * <p>测什么：拦截范围的三个例外（开关关掉、非 POST、cancel）、全局桶拒绝时的
 * 429 响应与两个响应头、<b>管理员不能吃掉学生桶</b>、降级时放行。
 *
 * <p>不测什么：令牌桶的补充数学（见 RateLimitServiceImplTest 与任务书 §4 的九项实测）、
 * 过滤器在 Security 链里的位置（那是 SecurityConfig 的装配问题，只能靠端到端验证 ——
 * 本类即使全绿，也可能因为漏写 addFilterAfter 而完全不生效，这个坑阶段 10 真的踩过）。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    private static final String GLOBAL_KEY = "seckill:rate:global";
    private static final String STUDENT_KEY_PREFIX = "seckill:rate:student:";

    @Mock
    private RateLimitService rateLimitService;

    private RateLimitFilter filter;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean chainCalled = new AtomicBoolean(false);
    private final FilterChain chain = (req, res) -> chainCalled.set(true);

    @BeforeEach
    void setUp() {
        filter = newFilter(true, 2000, 1500.0, 5, 1.0);
    }

    @AfterEach
    void tearDown() {
        // SecurityContextHolder 底层是 ThreadLocal，不清会串到下一个用例
        SecurityContextHolder.clearContext();
    }

    /** 按构造器参数顺序手写构造：7 个参数里 5 个是 @Value，单测要能任意组合。 */
    private RateLimitFilter newFilter(boolean enabled,
                                      int globalCapacity, double globalRefill,
                                      int studentCapacity, double studentRefill) {
        return new RateLimitFilter(rateLimitService, objectMapper, enabled,
                globalCapacity, globalRefill, studentCapacity, studentRefill);
    }

    private MockHttpServletRequest seckillRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/seckill/88");
        request.setRequestURI("/api/seckill/88");
        return request;
    }

    private void loginAs(Long principal, String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    // ==================== 拦截范围：三种情况必须直接放行 ====================

    @Test
    @DisplayName("开关关掉（rate-limit-enabled=false）→ 一次 Redis 都不碰，直接放行")
    void disabled_passesThroughWithoutTouchingRedis() throws Exception {
        RateLimitFilter disabled = newFilter(false, 2000, 1500.0, 5, 1.0);
        MockHttpServletResponse response = new MockHttpServletResponse();

        disabled.doFilter(seckillRequest(), response, chain);

        assertThat(chainCalled).isTrue();
        verify(rateLimitService, never()).tryAcquire(anyString(), anyInt(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("非 POST（GET 查课程）→ 放行，限流只管写操作")
    void nonPost_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/seckill/88");
        request.setRequestURI("/api/seckill/88");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chainCalled).isTrue();
        verify(rateLimitService, never()).tryAcquire(anyString(), anyInt(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("退课 /cancel → 放行。退课是减法操作，限流它只会让人退不掉")
    void cancel_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/seckill/88/cancel");
        request.setRequestURI("/api/seckill/88/cancel");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chainCalled).isTrue();
        verify(rateLimitService, never()).tryAcquire(anyString(), anyInt(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("POST 但不是抢课接口（如 /api/auth/login）→ 放行")
    void otherPostPath_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRequestURI("/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chainCalled).isTrue();
        verify(rateLimitService, never()).tryAcquire(anyString(), anyInt(), anyDouble(), anyInt());
    }

    // ==================== 全局桶拒绝 ====================

    @Test
    @DisplayName("全局桶拒绝 → 429 + Retry-After + X-RateLimit-Remaining，请求不进业务链")
    void globalBucketRejected_returns429() throws Exception {
        when(rateLimitService.tryAcquire(eq(GLOBAL_KEY), anyInt(), anyDouble(), anyInt()))
                .thenReturn(RateLimitResult.reject(0L, 1500L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(seckillRequest(), response, chain);

        assertThat(chainCalled)
                .as("被限流的请求不能再往下走，否则限流等于没做")
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        // 1500ms 向上取整到 2 秒：宁可让人多等，也别让人 1.4 秒就回来再撞一次
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"code\":429")
                .contains("系统繁忙");
        // 全局桶已经拒了，没必要再查学生桶
        verify(rateLimitService, never()).tryAcquire(
                eq(STUDENT_KEY_PREFIX + "1"), anyInt(), anyDouble(), anyInt());
    }

    // ==================== 最值钱的一条：管理员不吃学生桶 ====================

    @Test
    @DisplayName("🔴 管理员（ROLE_ADMIN）只消耗全局桶，不消耗学生桶")
    void adminNotLimitedByStudentBucket() throws Exception {
        // 故意用 id=1：sys_user.id=1 恰好等于 S000000001 的 id，
        // 不判角色的话管理员会静默吃掉学生 1 的配额（阶段 7 踩过同一个坑）
        loginAs(1L, "ADMIN");
        when(rateLimitService.tryAcquire(anyString(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(RateLimitResult.pass(100L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(seckillRequest(), response, chain);

        verify(rateLimitService).tryAcquire(eq(GLOBAL_KEY), anyInt(), anyDouble(), anyInt());
        verify(rateLimitService, never()).tryAcquire(
                eq(STUDENT_KEY_PREFIX + "1"), anyInt(), anyDouble(), anyInt());
    }

    // ==================== 学生桶 ====================

    @Test
    @DisplayName("学生放行 → 两个桶都查，学生 key 带上 studentId，请求继续")
    void studentAllowed_queriesBothBuckets() throws Exception {
        loginAs(1L, "STUDENT");
        when(rateLimitService.tryAcquire(anyString(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(RateLimitResult.pass(3L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(seckillRequest(), response, chain);

        assertThat(chainCalled).isTrue();
        verify(rateLimitService).tryAcquire(eq(GLOBAL_KEY), eq(2000), eq(1500.0), eq(1));
        verify(rateLimitService).tryAcquire(eq(STUDENT_KEY_PREFIX + "1"), eq(5), eq(1.0), eq(1));
    }

    @Test
    @DisplayName("学生桶拒绝 → 429，措辞与全局桶不同（一个说系统忙，一个说你太快）")
    void studentBucketRejected_returns429WithStudentMessage() throws Exception {
        loginAs(1L, "STUDENT");
        when(rateLimitService.tryAcquire(eq(GLOBAL_KEY), anyInt(), anyDouble(), anyInt()))
                .thenReturn(RateLimitResult.pass(1999L));
        when(rateLimitService.tryAcquire(eq(STUDENT_KEY_PREFIX + "1"), anyInt(), anyDouble(), anyInt()))
                .thenReturn(RateLimitResult.reject(0L, 1000L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(seckillRequest(), response, chain);

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).contains("操作过于频繁");
    }

    // ==================== 降级 ====================

    @Test
    @DisplayName("全局桶降级（Redis 挂了）→ 放行，不返回 429")
    void globalDegraded_passesThrough() throws Exception {
        loginAs(1L, "STUDENT");
        when(rateLimitService.tryAcquire(anyString(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(RateLimitResult.degradedPass());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(seckillRequest(), response, chain);

        assertThat(chainCalled)
                .as("Redis 挂了时限流必须让路：否则一个组件抖动会放大成全站 429")
                .isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("匿名请求（没有 Authentication）→ 只查全局桶，不查学生桶也不报错")
    void anonymous_onlyGlobalBucket() throws Exception {
        when(rateLimitService.tryAcquire(anyString(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(RateLimitResult.pass(1999L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(seckillRequest(), response, chain);

        assertThat(chainCalled).isTrue();
        verify(rateLimitService).tryAcquire(eq(GLOBAL_KEY), anyInt(), anyDouble(), anyInt());
        // 只查了一次 —— 全局桶。匿名请求没有 studentId，不该出现第二次调用
        verify(rateLimitService, times(1)).tryAcquire(anyString(), anyInt(), anyDouble(), anyInt());
    }
}
