package com.example.seckill.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.seckill.entity.Course;
import com.example.seckill.entity.CourseStock;
import com.example.seckill.entity.MqConsumeLog;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.entity.Student;
import com.example.seckill.entity.SysUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 纯 Mockito 单测的公共前置：把 MyBatis-Plus 的实体元数据注册进 JVM 静态缓存。
 *
 * <p>为什么需要：{@code LambdaQueryWrapper} 里的 lambda（如 {@code CourseStock::getStock}）
 * 要被翻译成 {@code "stock"} 这个列名，翻译过程依赖 MyBatis-Plus 的 {@code TableInfo} 缓存。
 *
 * <p>正常运行应用时这个缓存由 Spring 启动扫描实体自动填好；但纯 Mockito 单测没有 Spring 上下文，
 * 缓存是空的，一旦代码走到 wrapper 的构造或渲染就会抛：
 * <pre>MybatisPlusException: can not find lambda cache for this entity [xxx]</pre>
 *
 * <p>🔴 两个必须注意的点：
 * <ol>
 *   <li>这是<b>全 JVM 共享的静态缓存</b>，注册幂等，放在 {@code @BeforeAll static} 里调一次即可。
 *       放 {@code @BeforeEach} 虽然不会错，但每个用例重复注册纯属浪费。</li>
 *   <li>正因为它是全局的，<b>测试是否通过会依赖执行顺序</b>：如果同一 JVM 里先跑了
 *       {@code @SpringBootTest}（比如 DemoApplicationTests）把缓存预热了，后面的纯 Mockito 测试
 *       就会"搭便车"通过；而 CI 或随机执行顺序下没有预热，就会成片报错。
 *       所以每个纯 Mockito 测试类都必须自己调这个，不要指望别人。</li>
 * </ol>
 */
public final class TableInfoTestSupport {

    private TableInfoTestSupport() {
    }

    /**
     * 一次性注册项目里全部实体。
     *
     * <p>注册全部而不是"用到哪个注册哪个"，是为了以后新增测试时不用再想起这件事。
     * 注册本身只是往 map 里塞元数据，代价可忽略。
     */
    public static void initAll() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, Student.class);
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
        TableInfoHelper.initTableInfo(assistant, Course.class);
        TableInfoHelper.initTableInfo(assistant, CourseStock.class);
        TableInfoHelper.initTableInfo(assistant, SeckillOrder.class);
        TableInfoHelper.initTableInfo(assistant, MqConsumeLog.class);
    }
}
