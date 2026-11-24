package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.seckill.common.ResultCode;
import com.example.seckill.dto.CourseDetailResponse;
import com.example.seckill.entity.Course;
import com.example.seckill.entity.CourseStock;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.mapper.CourseMapper;
import com.example.seckill.mapper.CourseStockMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CourseServiceImpl 单元测试。
 *
 * <p>不启动 Spring、不连数据库：三个 Mapper 用 Mockito 打成假的，
 * 这样只测 CourseServiceImpl 自己的分支逻辑。
 *
 * <p>本类覆盖两块功能：
 * <ol>
 *   <li>{@code getCourseDetail}：单门课详情 + 当前学生的选课状态</li>
 *   <li>{@code listCourses}：全部课程列表 + 批量填选课状态（避免 N+1）</li>
 * </ol>
 *
 * <p>其中「当前学生是谁」这件事靠 {@code SecurityContextHolder} 判断，
 * 所以每个涉及身份的用例都要先 {@link #loginAs(Long, String)}，
 * 用完由 {@link #clearContext()} 清掉 —— 否则会污染后面的用例。
 */
@ExtendWith(MockitoExtension.class)          // 让 @Mock / @InjectMocks 生效
class CourseServiceImplTest {

    @Mock                                    // 造一个假的 CourseMapper，所有方法默认返回 null
    private CourseMapper courseMapper;

    @Mock                                    // 假的 CourseStockMapper
    private CourseStockMapper courseStockMapper;

    @Mock                                    // 假的 SeckillOrderMapper（判断"我选没选上"要用）
    private SeckillOrderMapper seckillOrderMapper;

    @InjectMocks                             // 把上面三个假的塞进 CourseServiceImpl 的构造器
    private CourseServiceImpl courseService;

    /**
     * 🔴 关键前置：初始化 MyBatis-Plus 的实体元数据。
     *
     * <p>为什么需要：{@code courseStockMapper.selectOne(new LambdaQueryWrapper<CourseStock>()
     * .eq(CourseStock::getCourseId, courseId))} 里的 lambda 要被翻译成
     * "course_id = ?" 这个 SQL 片段，翻译过程依赖 MyBatis-Plus 的 TableInfo 缓存。
     *
     * <p>正常运行应用时，这个缓存由 Spring 启动扫描实体自动填好；
     * 但纯 Mockito 单测没有 Spring 上下文，缓存是空的，会抛：
     * <pre>MybatisPlusException: can not find lambda cache for this entity [CourseStock]</pre>
     *
     * <p>而且它是<b>全 JVM 共享的静态缓存</b>，注册一次就够 —— 所以放 @BeforeAll，
     * 不要放 @BeforeEach（重复注册没意义）。
     *
     * <p>这里多注册了 {@link SeckillOrder}：新增的 myOrderStatus 逻辑会对它建
     * {@code LambdaQueryWrapper}，漏了就会在运行期炸。
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, Course.class);
        TableInfoHelper.initTableInfo(assistant, CourseStock.class);
        TableInfoHelper.initTableInfo(assistant, SeckillOrder.class);
    }

    /**
     * 清空安全上下文。
     *
     * <p>🔴 必须清：{@code SecurityContextHolder} 默认用 ThreadLocal 存身份，
     * 而 JUnit 默认在同一个线程里跑完所有用例。不清的话，
     * 前一个用例"登录成学生 1"的状态会漏到后一个用例里，
     * 导致后一个用例莫名其妙地拿到 myOrderStatus —— 而且这种失败极难查。
     */
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 测试用的小工厂，避免每个用例重复写 builder ====================

    /** 模拟"某个身份已登录"。principal 存的是用户 id（Long），和 JwtAuthenticationFilter 保持一致。 */
    private static void loginAs(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static Course course(long id) {
        return Course.builder()
                .id(id).courseName("课程" + id).teacher("教师" + id)
                .credit(new BigDecimal("2.5"))
                .startTime(LocalDateTime.of(2026, 11, 5, 16, 50, 28))
                .endTime(LocalDateTime.of(2027, 11, 6, 16, 50, 28))
                .build();
    }

    private static CourseStock stock(long courseId, int capacity, int stock, int sold) {
        return CourseStock.builder()
                .courseId(courseId).capacity(capacity).stock(stock).sold(sold).version(0)
                .build();
    }

    private static SeckillOrder order(long studentId, long courseId, int status) {
        return SeckillOrder.builder()
                .studentId(studentId).courseId(courseId).status(status)
                .build();
    }

    private static List<Course> courses(int count) {
        List<Course> list = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            list.add(course(i));
        }
        return list;
    }

    // ==================== 分支 A：课程不存在 ====================

    @Test
    @DisplayName("课程不存在 → 抛 404 业务异常")
    void getCourseDetail_notFound_throws404() {
        // 1) 打桩：告诉假的 mapper "查 99999 号课程时，你就返回 null"
        //    （等价于库里没有这门课）
        when(courseMapper.selectById(99999L)).thenReturn(null);

        // 2) 断言：执行查询时应该"抛异常"，而不是"返回 null"
        assertThatThrownBy(() -> courseService.getCourseDetail(99999L))
                // 2.1 异常类型必须是 BusinessException
                //     为什么用 isInstanceOf 而不是 isExactlyInstanceOf：以后可能有子类，用 isInstanceOf 更稳
                .isInstanceOf(BusinessException.class)
                // 2.2 光断言类型不够！BusinessException 是通用异常，404 和 409 都用它。
                //     所以必须再断言 code == 404，否则代码把 404 写成 409 测试照样绿。
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.NOT_FOUND))
                // 2.3 连提示语一起断言：防止以后有人把"课程不存在"改成空白/乱码
                .hasMessage("课程不存在");
    }

    // ==================== 分支 B：课程存在但库存行缺失 ====================

    @Test
    @DisplayName("课程存在但库存行缺失 → 按全 0 返回，不抛异常")
    void getCourseDetail_stockMissing_returnsZeroStock() {
        when(courseMapper.selectById(1L)).thenReturn(course(1L));
        when(courseStockMapper.selectOne(any())).thenReturn(null);   // ← 库存行缺失

        CourseDetailResponse response = courseService.getCourseDetail(1L);

        assertThat(response.getCapacity()).isZero();
        assertThat(response.getStock()).isZero();
        assertThat(response.getSold()).isZero();

        //       同时课程信息（courseName/teacher/credit）依然正确
        assertThat(response.getCourseId()).isEqualTo(1L);
        assertThat(response.getCourseName()).isEqualTo("课程1");
        assertThat(response.getTeacher()).isEqualTo("教师1");
        assertThat(response.getCredit()).isEqualByComparingTo("2.5");
    }

    // ==================== 分支 C：正常路径 ====================

    @Test
    @DisplayName("课程与库存都正常 → 9 个字段全部正确组装")
    void getCourseDetail_success_assemblesAllFields() {
        when(courseMapper.selectById(7L)).thenReturn(course(7L));
        when(courseStockMapper.selectOne(any())).thenReturn(stock(7L, 100, 63, 37));

        CourseDetailResponse result = courseService.getCourseDetail(7L);

        assertThat(result.getCourseId()).isEqualTo(7L);
        assertThat(result.getCourseName()).isEqualTo("课程7");
        assertThat(result.getTeacher()).isEqualTo("教师7");
        assertThat(result.getCredit()).isEqualByComparingTo("2.5");   // ← BigDecimal 要用这个
        assertThat(result.getStartTime()).isEqualTo(LocalDateTime.of(2026, 11, 5, 16, 50, 28));
        assertThat(result.getEndTime()).isEqualTo(LocalDateTime.of(2027, 11, 6, 16, 50, 28));
        assertThat(result.getCapacity()).isEqualTo(100);
        assertThat(result.getStock()).isEqualTo(63);
        assertThat(result.getSold()).isEqualTo(37);

        // 账目守恒：名额 = 剩余 + 已售
        assertThat(result.getStock() + result.getSold()).isEqualTo(result.getCapacity());
    }

    // ==================== 分支 D：详情接口填 myOrderStatus ====================

    /**
     * 这条用例防的是「抢课成功但界面看不到」这个真实 bug。
     *
     * <p>前端 {@code index.html} 的 pollOrderStatus 靠详情接口的 myOrderStatus 判断结果：
     * 0 继续轮询、1 弹"选课成功"、2 弹"选课失败"。如果这里返回 null，
     * 三个分支都不进 → 轮询一次就停 → 按钮永远卡在"抢课中"。
     */
    @Test
    @DisplayName("学生已选上 → 详情返回 myOrderStatus=1")
    void getCourseDetail_studentHasOrder_fillsMyOrderStatus() {
        loginAs(1L, "STUDENT");
        when(courseMapper.selectById(3L)).thenReturn(course(3L));
        when(courseStockMapper.selectOne(any())).thenReturn(stock(3L, 100, 99, 1));
        when(seckillOrderMapper.selectOne(any()))
                .thenReturn(order(1L, 3L, SeckillOrder.STATUS_SUCCESS));

        CourseDetailResponse result = courseService.getCourseDetail(3L);

        assertThat(result.getMyOrderStatus()).isEqualTo(SeckillOrder.STATUS_SUCCESS);
        assertThat(result.getStock()).isEqualTo(99);
    }

    @Test
    @DisplayName("学生已退课 → 详情返回 myOrderStatus=3（前端显示「重新选课」）")
    void getCourseDetail_cancelledOrder_fillsCancelledStatus() {
        loginAs(1L, "STUDENT");
        when(courseMapper.selectById(3L)).thenReturn(course(3L));
        when(courseStockMapper.selectOne(any())).thenReturn(stock(3L, 100, 100, 0));
        when(seckillOrderMapper.selectOne(any()))
                .thenReturn(order(1L, 3L, SeckillOrder.STATUS_CANCELLED));

        CourseDetailResponse result = courseService.getCourseDetail(3L);

        assertThat(result.getMyOrderStatus()).isEqualTo(SeckillOrder.STATUS_CANCELLED);
    }

    @Test
    @DisplayName("学生没选过这门课 → myOrderStatus 为 null，不报错")
    void getCourseDetail_studentWithoutOrder_returnsNullStatus() {
        loginAs(1L, "STUDENT");
        when(courseMapper.selectById(3L)).thenReturn(course(3L));
        when(courseStockMapper.selectOne(any())).thenReturn(stock(3L, 100, 100, 0));
        when(seckillOrderMapper.selectOne(any())).thenReturn(null);

        CourseDetailResponse result = courseService.getCourseDetail(3L);

        assertThat(result.getMyOrderStatus()).isNull();
    }

    /**
     * 🔴 这条是最重要的身份用例。
     *
     * <p>为什么必须测：{@code sys_user} 里管理员的 id 恰好是 1，
     * 而 {@code student} 里 S000000001 的 id 也是 1 —— 两边 id 撞了。
     * 如果 currentStudentId() 只看 principal 不判角色，
     * 管理员打开页面就会看到"学生 1 的选课状态"，而且不报任何错。
     *
     * <p>所以这里故意用 <b>同一个 id=1</b> 登录成 ADMIN，断言必须为 null。
     * 一旦有人把角色判断删了，这条会立刻变红。
     */
    @Test
    @DisplayName("管理员（id 与学生 1 相同）→ myOrderStatus 必须为 null")
    void getCourseDetail_adminWithCollidingId_returnsNullStatus() {
        loginAs(1L, "ADMIN");
        when(courseMapper.selectById(3L)).thenReturn(course(3L));
        when(courseStockMapper.selectOne(any())).thenReturn(stock(3L, 100, 99, 1));

        CourseDetailResponse result = courseService.getCourseDetail(3L);

        assertThat(result.getMyOrderStatus()).isNull();
        // 连订单表都不该查：管理员根本没有"我的选课"这个概念
        verifyNoInteractions(seckillOrderMapper);
    }

    @Test
    @DisplayName("未登录 → myOrderStatus 为 null，且不查订单表")
    void getCourseDetail_noAuthentication_returnsNullStatus() {
        // 不调 loginAs，SecurityContextHolder 是空的
        when(courseMapper.selectById(3L)).thenReturn(course(3L));
        when(courseStockMapper.selectOne(any())).thenReturn(stock(3L, 100, 100, 0));

        CourseDetailResponse result = courseService.getCourseDetail(3L);

        assertThat(result.getMyOrderStatus()).isNull();
        verifyNoInteractions(seckillOrderMapper);
    }

    @Test
    @DisplayName("principal 不是 Long（防御）→ 当作未登录处理，不抛 ClassCastException")
    void getCourseDetail_principalNotLong_treatedAsAnonymous() {
        // 正常链路里 principal 一定是 Long（JwtAuthenticationFilter 塞的 userId）。
        // 但万一以后换了认证方式塞了 String/UserDetails，这里不能直接抛 ClassCastException。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "S000000001", null,
                        List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        when(courseMapper.selectById(3L)).thenReturn(course(3L));
        when(courseStockMapper.selectOne(any())).thenReturn(stock(3L, 100, 100, 0));

        CourseDetailResponse result = courseService.getCourseDetail(3L);

        assertThat(result.getMyOrderStatus()).isNull();
        verifyNoInteractions(seckillOrderMapper);
    }

    // ==================== 分支 E：课程列表 ====================

    @Test
    @DisplayName("列表 → 返回全部课程、按 id 升序、只给选过的那门填状态")
    void listCourses_returnsAllWithMyStatus() {
        loginAs(1L, "STUDENT");
        List<Course> all = courses(100);
        List<CourseStock> stocks = all.stream()
                .map(c -> stock(c.getId(), 100, 100, 0))
                .collect(Collectors.toList());
        when(courseMapper.selectList(any())).thenReturn(all);
        when(courseStockMapper.selectList(any())).thenReturn(stocks);
        // 该学生只选了课程 3
        when(seckillOrderMapper.selectList(any()))
                .thenReturn(List.of(order(1L, 3L, SeckillOrder.STATUS_SUCCESS)));

        List<CourseDetailResponse> result = courseService.listCourses();

        assertThat(result).hasSize(100);
        // 顺序：id 从 1 到 100 依次递增
        assertThat(result).extracting(CourseDetailResponse::getCourseId)
                .containsExactlyElementsOf(
                        all.stream().map(Course::getId).collect(Collectors.toList()));

        // 只有课程 3 带状态，其它都是 null
        assertThat(result.get(2).getCourseId()).isEqualTo(3L);
        assertThat(result.get(2).getMyOrderStatus()).isEqualTo(SeckillOrder.STATUS_SUCCESS);
        assertThat(result.get(0).getMyOrderStatus()).isNull();
        assertThat(result.get(99).getMyOrderStatus()).isNull();

        // 库存字段正确透传
        assertThat(result.get(0).getCapacity()).isEqualTo(100);
        assertThat(result.get(0).getStock()).isEqualTo(100);
    }

    /**
     * 这条守的是「避免 N+1」这个设计意图。
     *
     * <p>listCourses 只查一次订单表（拿到该学生所有订单做成 Map），
     * 而不是每门课查一次。100 门课如果循环查就是 100 次 SQL。
     * 断言 times(1) 就是把这个约束钉死 —— 以后有人改成循环查，测试会红。
     */
    @Test
    @DisplayName("列表 → 订单表只查 1 次，不是每门课查一次（防 N+1）")
    void listCourses_queriesOrdersOnlyOnce() {
        loginAs(1L, "STUDENT");
        List<Course> all = courses(20);
        when(courseMapper.selectList(any())).thenReturn(all);
        when(courseStockMapper.selectList(any()))
                .thenReturn(all.stream().map(c -> stock(c.getId(), 100, 100, 0))
                        .collect(Collectors.toList()));
        when(seckillOrderMapper.selectList(any())).thenReturn(List.of());

        courseService.listCourses();

        verify(seckillOrderMapper, org.mockito.Mockito.times(1)).selectList(any());
    }

    @Test
    @DisplayName("列表 → 管理员看所有课 myOrderStatus 都是 null")
    void listCourses_admin_allStatusNull() {
        loginAs(1L, "ADMIN");
        List<Course> all = courses(10);
        when(courseMapper.selectList(any())).thenReturn(all);
        when(courseStockMapper.selectList(any()))
                .thenReturn(all.stream().map(c -> stock(c.getId(), 100, 100, 0))
                        .collect(Collectors.toList()));

        List<CourseDetailResponse> result = courseService.listCourses();

        assertThat(result).hasSize(10);
        assertThat(result).allSatisfy(r -> assertThat(r.getMyOrderStatus()).isNull());
        verifyNoInteractions(seckillOrderMapper);
    }

    @Test
    @DisplayName("列表 → 某门课库存行缺失时按全 0 返回，不影响其它课程")
    void listCourses_missingStockRow_returnsZerosForThatCourse() {
        List<Course> all = courses(3);
        when(courseMapper.selectList(any())).thenReturn(all);
        // 故意只给课程 1 和 3 库存，课程 2 缺失
        when(courseStockMapper.selectList(any()))
                .thenReturn(List.of(stock(1L, 100, 100, 0), stock(3L, 50, 20, 30)));

        List<CourseDetailResponse> result = courseService.listCourses();

        assertThat(result).hasSize(3);
        assertThat(result.get(1).getCourseId()).isEqualTo(2L);
        assertThat(result.get(1).getCapacity()).isZero();
        assertThat(result.get(1).getStock()).isZero();
        assertThat(result.get(1).getSold()).isZero();
        // 其它课程不受影响
        assertThat(result.get(0).getCapacity()).isEqualTo(100);
        assertThat(result.get(2).getCapacity()).isEqualTo(50);
        assertThat(result.get(2).getStock()).isEqualTo(20);
    }

    @Test
    @DisplayName("列表 → 未登录时也能看（只是没有我的选课状态）")
    void listCourses_anonymous_returnsListWithoutStatus() {
        List<Course> all = courses(5);
        when(courseMapper.selectList(any())).thenReturn(all);
        when(courseStockMapper.selectList(any()))
                .thenReturn(all.stream().map(c -> stock(c.getId(), 100, 100, 0))
                        .collect(Collectors.toList()));

        List<CourseDetailResponse> result = courseService.listCourses();

        assertThat(result).hasSize(5);
        assertThat(result).allSatisfy(r -> assertThat(r.getMyOrderStatus()).isNull());
        verify(seckillOrderMapper, never()).selectList(any());
    }
}
