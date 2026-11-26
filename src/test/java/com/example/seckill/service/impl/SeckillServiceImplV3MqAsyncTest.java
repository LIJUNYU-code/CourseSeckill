package com.example.seckill.service.impl;

import com.example.seckill.common.ResultCode;
import com.example.seckill.dto.SeckillResponse;
import com.example.seckill.entity.Course;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.infra.cache.StockCacheService;
import com.example.seckill.infra.mq.SeckillMessage;
import com.example.seckill.infra.mq.SeckillMessageProducer;
import com.example.seckill.mapper.CourseMapper;
import com.example.seckill.mapper.CourseStockMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.support.TableInfoTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SeckillServiceImplV3MqAsync 单元测试。
 *
 * <p>覆盖阶段 7 任务④：抢课接口里 {@code updateStatusIfMatch} 的返回值必须检查。
 *
 * <p>🔴 这条缺陷的特殊之处在于它<b>完全静默</b>：
 * 不超卖、不报错、日志里连 WARN 都没有，唯一的痕迹是 Redis 里少了一个名额。
 * 触发条件是「同一学生并发双击 + 退课后重选」，人工点一次永远复现不了。
 * 所以只能靠单测把这条路径钉死。
 *
 * <p>四个用例的分工：
 * <ol>
 *   <li>CAS 返回 0 → 抛 409，文案含「频繁」（用户能看懂，不是 500）</li>
 *   <li>CAS 返回 0 → <b>一条消息都不许发</b>（发了就会多扣一次 MySQL）</li>
 *   <li>CAS 返回 0 → Redis 必须补回 1 个（finally 里的补偿）</li>
 *   <li>CAS 返回 1 → 正常发消息，且 Redis <b>不许</b>补（补了就多一个名额）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SeckillServiceImplV3MqAsyncTest {

    private static final Long COURSE_ID = 88L;
    private static final Long STUDENT_ID = 1L;

    @Mock
    private CourseMapper courseMapper;

    @Mock
    private CourseStockMapper courseStockMapper;

    @Mock
    private SeckillOrderMapper seckillOrderMapper;

    @Mock
    private StockCacheService stockCacheService;

    @Mock
    private SeckillMessageProducer messageProducer;

    @Mock
    private TransactionTemplate transactionTemplate;

    private SeckillServiceImplV3MqAsync service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoTestSupport.initAll();
    }

    @BeforeEach
    void setUp() {
        // @AllArgsConstructor 的字段顺序：courseMapper, courseStockMapper, seckillOrderMapper,
        // stockCacheService, messageProducer, transactionTemplate
        service = new SeckillServiceImplV3MqAsync(
                courseMapper, courseStockMapper, seckillOrderMapper,
                stockCacheService, messageProducer, transactionTemplate);

        // 登录成学生
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        STUDENT_ID, null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 辅助方法 ====================

    /** 一门正在选课窗口内的课程。 */
    private Course openCourse() {
        Course course = new Course();
        course.setId(COURSE_ID);
        course.setCourseName("课程88");
        course.setTeacher("教师88");
        course.setCredit(new BigDecimal("2.5"));
        course.setStartTime(LocalDateTime.now().minusDays(1));
        course.setEndTime(LocalDateTime.now().plusDays(1));
        return course;
    }

    /** 一条已退课的历史订单（CAS 的目标：3 → 0）。 */
    private SeckillOrder cancelledOrder() {
        return SeckillOrder.builder()
                .id(100L)
                .orderNo("O-CANCELLED")
                .studentId(STUDENT_ID)
                .courseId(COURSE_ID)
                .status(SeckillOrder.STATUS_CANCELLED)
                .build();
    }

    /**
     * 铺好「走到 CAS 那一步」的全部前置条件。
     *
     * @param casAffected CAS 的返回值：0 = 被别人抢先改，1 = 抢到了
     */
    private void stubUpToCas(int casAffected) {
        when(courseMapper.selectById(COURSE_ID)).thenReturn(openCourse());
        when(stockCacheService.tryDecrement(COURSE_ID)).thenReturn(99L);
        when(seckillOrderMapper.selectOne(any())).thenReturn(cancelledOrder());
        when(seckillOrderMapper.updateStatusIfMatch(
                any(), any(Integer.class), any(Integer.class))).thenReturn(casAffected);
    }

    // ==================== 用例 1：CAS 返回 0 的对外表现 ====================

    @Test
    @DisplayName("🔴 CAS 返回 0：抛 409 且文案含「频繁」，不是 500 也不是静默通过")
    void seckill_casLost_throwsConflictWithFriendlyMessage() {
        stubUpToCas(0);

        assertThatThrownBy(() -> service.seckill(COURSE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.CONFLICT))
                .hasMessageContaining("频繁");
    }

    // ==================== 用例 2：CAS 返回 0 不许发消息 ====================

    @Test
    @DisplayName("🔴 CAS 返回 0：一条消息都不许发（发了消费者就会多扣一次 MySQL 库存）")
    void seckill_casLost_neverSendsMessage() {
        stubUpToCas(0);

        assertThatThrownBy(() -> service.seckill(COURSE_ID))
                .isInstanceOf(BusinessException.class);

        verify(messageProducer, never()).send(any(SeckillMessage.class));
    }

    // ==================== 用例 3：CAS 返回 0 必须补回 Redis ====================

    @Test
    @DisplayName("🔴 CAS 返回 0：Redis 必须补回 1 个（门禁已经扣过了，不补就永久偏低）")
    void seckill_casLost_returnsRedisExactlyOnce() {
        stubUpToCas(0);

        assertThatThrownBy(() -> service.seckill(COURSE_ID))
                .isInstanceOf(BusinessException.class);

        // 只能补一次：多补会让名额凭空变多
        verify(stockCacheService, times(1)).incrementIfPresent(COURSE_ID);
    }

    // ==================== 用例 4：CAS 返回 1 的正常路径 ====================

    @Test
    @DisplayName("CAS 返回 1：正常发消息、返回待处理状态，且 Redis 绝不能被补")
    void seckill_casWon_sendsMessageAndKeepsRedis() {
        stubUpToCas(1);
        when(messageProducer.send(any(SeckillMessage.class))).thenReturn(true);

        SeckillResponse res = service.seckill(COURSE_ID);

        assertThat(res.getOrderNo()).isEqualTo("O-CANCELLED");
        assertThat(res.getStatus()).isEqualTo(SeckillOrder.STATUS_PENDING);

        // 消息发了 1 条
        verify(messageProducer, times(1)).send(any(SeckillMessage.class));
        // 🔴 成功了就不能补 Redis —— 补了就是凭空多一个名额
        verify(stockCacheService, never()).incrementIfPresent(any());
    }
}
