package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.seckill.common.ResultCode;
import com.example.seckill.dto.CourseDetailResponse;
import com.example.seckill.entity.Course;
import com.example.seckill.entity.CourseStock;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.mapper.CourseMapper;
import com.example.seckill.mapper.CourseStockMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * CourseServiceImpl 单元测试。
 *
 * <p>不启动 Spring、不连数据库：两个 Mapper 用 Mockito 打成假的，
 * 这样只测 CourseServiceImpl 自己的分支逻辑。
 */
@ExtendWith(MockitoExtension.class)          // 让 @Mock / @InjectMocks 生效
class CourseServiceImplTest {

    @Mock                                    // 造一个假的 CourseMapper，所有方法默认返回 null
    private CourseMapper courseMapper;

    @Mock                                    // 假的 CourseStockMapper
    private CourseStockMapper courseStockMapper;

    @InjectMocks                             // 把上面两个假的塞进 CourseServiceImpl 的构造器
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
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, Course.class);
        TableInfoHelper.initTableInfo(assistant, CourseStock.class);
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
        Course course = Course.builder()
                .id(1L).courseName("课程1").teacher("教师1")
                .credit(new BigDecimal("2.5"))
                .startTime(LocalDateTime.of(2026, 11, 5, 16, 50, 28))
                .endTime(LocalDateTime.of(2027, 11, 6, 16, 50, 28))
                .build();
        when(courseMapper.selectById(1L)).thenReturn(course);
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
        Course course = Course.builder()
                .id(7L).courseName("课程7").teacher("教师7")
                .credit(new BigDecimal("3.5"))
                .startTime(LocalDateTime.of(2026, 11, 5, 16, 50, 28))
                .endTime(LocalDateTime.of(2027, 11, 6, 16, 50, 28))
                .build();
        CourseStock stock = CourseStock.builder()
                .courseId(7L).capacity(100).stock(63).sold(37).version(0)
                .build();
        when(courseMapper.selectById(7L)).thenReturn(course);
        when(courseStockMapper.selectOne(any())).thenReturn(stock);

        CourseDetailResponse result = courseService.getCourseDetail(7L);

        assertThat(result.getCourseId()).isEqualTo(7L);
        assertThat(result.getCourseName()).isEqualTo("课程7");
        assertThat(result.getTeacher()).isEqualTo("教师7");
        assertThat(result.getCredit()).isEqualByComparingTo("3.5");   // ← BigDecimal 要用这个
        assertThat(result.getStartTime()).isEqualTo(LocalDateTime.of(2026, 11, 5, 16, 50, 28));
        assertThat(result.getEndTime()).isEqualTo(LocalDateTime.of(2027, 11, 6, 16, 50, 28));
        assertThat(result.getCapacity()).isEqualTo(100);
        assertThat(result.getStock()).isEqualTo(63);
        assertThat(result.getSold()).isEqualTo(37);

        assertThat(result.getStock() + result.getSold()).isEqualTo(result.getCapacity());

    }
}