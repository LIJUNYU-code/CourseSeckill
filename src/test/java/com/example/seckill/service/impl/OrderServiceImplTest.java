package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.seckill.dto.MyOrderPageResponse;
import com.example.seckill.dto.OrderResponse;
import com.example.seckill.entity.Course;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.mapper.CourseMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.support.TableInfoTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderServiceImpl 单元测试。
 *
 * <p>重点覆盖阶段 7 任务②修掉的 <b>N+1</b>：原来每单一次 {@code courseMapper.selectById}，
 * 一页 10 单就是 11 次 SQL。改成一次 {@code selectByIds} 批量查。
 *
 * <p>核心断言是 {@code verify(times(1))} 与 {@code verify(never())} 这一对——
 * 它们不测功能对不对，测的是「别退回到 N 次查询」。
 * 这类断言叫<b>性能回归测试</b>：功能测试发现不了 N+1（结果一样对），
 * 只有数调用次数才抓得住。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private SeckillOrderMapper orderMapper;

    @Mock
    private CourseMapper courseMapper;

    @InjectMocks
    private OrderServiceImpl orderService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoTestSupport.initAll();
    }

    /**
     * 清空安全上下文。
     *
     * <p>{@code SecurityContextHolder} 用 ThreadLocal 存身份，JUnit 默认同线程跑完所有用例，
     * 不清的话「登录成学生 1」的状态会漏到下一个用例。
     */
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 辅助方法 ====================

    /** 模拟「当前登录的学生」。principal 必须是 Long，Service 里直接强转。 */
    private void loginAs(Long studentId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        studentId, null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    private SeckillOrder order(String orderNo, Long courseId, int status) {
        return SeckillOrder.builder()
                .id(1L)
                .orderNo(orderNo)
                .studentId(1L)
                .courseId(courseId)
                .status(status)
                .createTime(LocalDateTime.of(2026, 1, 1, 12, 0))
                .updateTime(LocalDateTime.of(2026, 1, 1, 12, 0))
                .build();
    }

    private Course course(Long id, String name) {
        Course c = new Course();
        c.setId(id);
        c.setCourseName(name);
        return c;
    }

    /**
     * 让 {@code orderMapper.selectPage(...)} 返回指定的一页数据。
     *
     * <p>用 {@code any()} 匹配参数：{@code Page} 与 {@code LambdaQueryWrapper} 都没重写 equals，
     * 写具体值永远匹配不上。
     */
    private void stubPage(List<SeckillOrder> records, long total) {
        when(orderMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<SeckillOrder> p = inv.getArgument(0);
            p.setRecords(records);
            p.setTotal(total);
            return p;
        });
    }

    // ==================== queryOrders：N+1 回归 ====================

    @Test
    @DisplayName("一页 3 单 3 门不同课：课程只批量查 1 次，绝不逐单查")
    void queryOrders_batchesCoursesInsteadOfNPlusOne() {
        loginAs(1L);
        stubPage(List.of(
                order("O1", 10L, SeckillOrder.STATUS_SUCCESS),
                order("O2", 20L, SeckillOrder.STATUS_SUCCESS),
                order("O3", 30L, SeckillOrder.STATUS_SUCCESS)), 3L);
        when(courseMapper.selectByIds(anyCollection()))
                .thenReturn(List.of(course(10L, "课程10"), course(20L, "课程20"), course(30L, "课程30")));

        MyOrderPageResponse res = orderService.queryOrders(1, 10);

        assertThat(res.getRecords()).hasSize(3);
        assertThat(res.getRecords()).extracting(OrderResponse::getCourseName)
                .containsExactly("课程10", "课程20", "课程30");

        // 🔴 核心断言：批量查 1 次，逐单查 0 次
        verify(courseMapper, times(1)).selectByIds(anyCollection());
        verify(courseMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("一页 3 单只有 2 门课（重复课程）：字典按 id 命中，3 条都填对")
    void queryOrders_duplicateCourseIds_allFilled() {
        loginAs(1L);
        stubPage(List.of(
                order("O1", 10L, SeckillOrder.STATUS_SUCCESS),
                order("O2", 20L, SeckillOrder.STATUS_SUCCESS),
                order("O3", 10L, SeckillOrder.STATUS_CANCELLED)), 3L);
        when(courseMapper.selectByIds(anyCollection()))
                .thenReturn(List.of(course(10L, "课程10"), course(20L, "课程20")));

        MyOrderPageResponse res = orderService.queryOrders(1, 10);

        assertThat(res.getRecords()).extracting(OrderResponse::getCourseName)
                .containsExactly("课程10", "课程20", "课程10");
    }

    @Test
    @DisplayName("某门课查不到：降级为「课程已删除」，其余订单正常返回，不抛异常")
    void queryOrders_missingCourse_degradesInsteadOfThrowing() {
        loginAs(1L);
        stubPage(List.of(
                order("O1", 10L, SeckillOrder.STATUS_SUCCESS),
                order("O2", 99L, SeckillOrder.STATUS_SUCCESS)), 2L);
        // 课程 99 已被删，批量查询只返回 10
        when(courseMapper.selectByIds(anyCollection()))
                .thenReturn(List.of(course(10L, "课程10")));

        MyOrderPageResponse res = orderService.queryOrders(1, 10);

        assertThat(res.getRecords()).hasSize(2);
        assertThat(res.getRecords().get(0).getCourseName()).isEqualTo("课程10");
        assertThat(res.getRecords().get(1).getCourseName()).isEqualTo("课程已删除");
    }

    @Test
    @DisplayName("空列表：total=0、records 空，课程表零查询")
    void queryOrders_emptyPage_noCourseQuery() {
        loginAs(1L);
        stubPage(List.of(), 0L);

        MyOrderPageResponse res = orderService.queryOrders(1, 10);

        assertThat(res.getTotal()).isZero();
        assertThat(res.getRecords()).isEmpty();
        // 提前 return，连批量查都不该发
        verify(courseMapper, never()).selectByIds(anyCollection());
        verify(courseMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("状态文案：0→待处理 1→已成功 2→已失败 3→已退课")
    void queryOrders_statusText() {
        loginAs(1L);
        stubPage(List.of(
                order("O0", 10L, SeckillOrder.STATUS_PENDING),
                order("O1", 10L, SeckillOrder.STATUS_SUCCESS),
                order("O2", 10L, SeckillOrder.STATUS_FAILED),
                order("O3", 10L, SeckillOrder.STATUS_CANCELLED)), 4L);
        when(courseMapper.selectByIds(anyCollection()))
                .thenReturn(List.of(course(10L, "课程10")));

        MyOrderPageResponse res = orderService.queryOrders(1, 10);

        assertThat(res.getRecords()).extracting(OrderResponse::getStatusText)
                .containsExactly("待处理", "已成功", "已失败", "已退课");
    }

    @Test
    @DisplayName("分页字段原样回传：total/page/size 不被改写")
    void queryOrders_echoesPagingFields() {
        loginAs(1L);
        stubPage(List.of(order("O1", 10L, SeckillOrder.STATUS_SUCCESS)), 42L);
        when(courseMapper.selectByIds(anyCollection()))
                .thenReturn(List.of(course(10L, "课程10")));

        MyOrderPageResponse res = orderService.queryOrders(3, 20);

        assertThat(res.getTotal()).isEqualTo(42L);
        assertThat(res.getPage()).isEqualTo(3);
        assertThat(res.getSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("批量查询收到的是去重后的 courseId 集合")
    void queryOrders_deduplicatesCourseIds() {
        loginAs(1L);
        stubPage(List.of(
                order("O1", 10L, SeckillOrder.STATUS_SUCCESS),
                order("O2", 10L, SeckillOrder.STATUS_CANCELLED),
                order("O3", 10L, SeckillOrder.STATUS_FAILED)), 3L);
        when(courseMapper.selectByIds(anyCollection()))
                .thenReturn(List.of(course(10L, "课程10")));

        orderService.queryOrders(1, 10);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<Long>> captor =
                org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(courseMapper).selectByIds(captor.capture());
        // 3 单同一门课 → 集合里只有 1 个元素（Set 去重生效）
        assertThat(new ArrayList<>(captor.getValue())).containsExactly(10L);
    }
}
