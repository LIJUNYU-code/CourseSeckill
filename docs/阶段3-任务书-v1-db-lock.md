# 阶段 3 任务书：v1-db-lock（数据库锁）

> 前置版本：`v0-baseline`（tag 已打，提交 `05d88be`）
> 本阶段目标：**同样的 300 并发、同样的 100 个名额，把成功单数从 300 压回 100，账目缺口从 −296 压回 0。**
> 预计工作量：1 周（10 小时）

---

## 0. 这一阶段要解决什么

v0 的实测数字摆在这里，这就是 v1 的靶子：

| 版本 | 并发 | 成功单 | 名额 | 超卖 | `stock` | `sold` | 账目缺口 | P95 |
|---|---|---|---|---|---|---|---|---|
| v0-baseline | 300 | **300** | 100 | **+200** | 96 | 300 | **−296** | 572 ms |

v1 要做的事只有一句：**让「判断库存」和「扣减库存」变成一个不可分割的原子操作。**

---

## 1. 前置事实（全部实测，写代码前先确认这些）

| 项 | 当前状态 | 对本阶段的影响 |
|---|---|---|
| `seckill.strategy` | `v0-baseline`（`application.yaml:84`） | 要靠它切换三套实现 |
| `@Transactional` 使用 | **全项目零命中**（v0 故意不加） | v1 必须加上，这是本阶段最大的行为变化 |
| `BusinessException` 父类 | `extends RuntimeException` | ✅ 默认就会回滚事务，不用写 `rollbackFor` |
| `course_stock.version` 列 | 存在，当前全部为 `0` | 实现 C 要用；实现 A/B 用不到 |
| `businessException` 的 HTTP 码 | `ResultCode.CONFLICT` = 409 | 压测里「名额已满」和「已选过」都是 409，靠 message 区分 |
| MySQL 事务隔离级别 | `REPEATABLE-READ` | 影响 `SELECT FOR UPDATE` 读到什么，见 §5.3 |
| `innodb_lock_wait_timeout` | **50 秒** | 太长了，见 §5.5 |
| Hikari `connection-timeout` | 3000 ms | 悲观锁的关键约束，见 §5.5 |
| Tomcat `max-threads` | 200 | 300 并发时会有 100 个请求在 Tomcat 队列里等 |
| 现有 Service 实现 | `SeckillServiceImpl`（v0） | 新实现并列，靠 `@ConditionalOnProperty` 互斥 |

**关键一点**：`SeckillServiceImpl` 上有 `matchIfMissing = true`。这意味着如果你把 strategy 写成 `v1-optimistc`（少个 i），**没有任何 bean 会加载** → 启动报 `No qualifying bean of type 'SeckillService'`。

这是个好现象（快速失败），但要知道为什么报错。

---

## 2. 三个实现与对应的配置值

| 配置值 | 实现类 | 扣减方式 | 必做 |
|---|---|---|---|
| `v0-baseline` | `SeckillServiceImpl`（已存在） | 读 → 算 → 写绝对值 | 已完成 |
| `v1-optimistic` | `SeckillServiceImplV1Optimistic` | 条件 UPDATE，看 affected rows | ✅ |
| `v1-pessimistic` | `SeckillServiceImplV1Pessimistic` | `SELECT ... FOR UPDATE` 再更新 | ✅ |
| `v1-version-cas` | `SeckillServiceImplV1VersionCas` | `WHERE version = ?` CAS + 重试 | 选做，强烈建议 |

**为什么三份代码不抽公共基类**：

模板方法模式确实能消掉 90% 的重复，但这里**故意不抽**。理由是这三份代码的用途不是"生产运行"，而是**给人对着读、给面试官对着讲**。抽了基类以后，看代码的人要在三个文件之间跳来跳去才能理解"差别只有一条 SQL"。

等三份都能跑通、数字都拿到之后再决定要不要重构——那时候你才知道哪个抽象是对的。**先让它工作，再让它干净。**

---

## 3. 核心原理：`affected rows` 是唯一可信的返回值

这是整个 v1 最重要的一句话。

```java
// ❌ 错的：先查再判断
CourseStock s = mapper.selectByCourseId(id);
if (s.getStock() > 0) {
    mapper.update(id, s.getStock() - 1);   // 这中间别人可能已经改了
}

// ✅ 对的：判断和扣减在一条 SQL 里
int affected = mapper.tryDecrement(id);
// affected = 1 → 抢到
// affected = 0 → 库存已经没了
```

**为什么 `affected` 可信而 `getStock()` 不可信**：

`UPDATE ... WHERE stock > 0` 这一条语句内部，MySQL 会先拿到行锁、读出当前值、判断条件、写入新值、释放锁——**这四步在数据库内部是原子的**。返回的 `affected` 是这个原子操作的真实结果。

而 `s.getStock()` 是**另一个时刻**的快照。从读到写之间，可能有 50 个线程已经改过了。

> **面试要点**：判断条件必须和写操作在同一条 SQL 里。把它们拆成两步，即使两步各自都"看起来对"，加起来就是错的。

---

## 4. 实现 A：条件更新（`v1-optimistic`）

### 4.1 Mapper 新增一条 SQL

在 `src/main/java/com/example/seckill/mapper/CourseStockMapper.java` 里加：

```java
/**
 * 条件扣减：判断（stock > 0）与扣减在同一条 SQL 内部完成，数据库保证原子性。
 *
 * @return 受影响行数。1 = 扣减成功；0 = 库存不足或课程不存在
 */
@Update("UPDATE course_stock SET stock = stock - 1, sold = sold + 1 " +
        "WHERE course_id = #{courseId} AND stock > 0")
int tryDecrement(@Param("courseId") Long courseId);
```

**注意 `stock = stock - 1` 而不是 `stock = #{newStock}`**。

这正是 v0 超卖的直接原因：v0 把"我以为应该是多少"写回去，覆盖了别人的修改。改成 `stock - 1` 就是"在数据库当前值的基础上减一"，数据库会自己保证这个读-改-写是原子的。

### 4.2 完整的 Service 实现

新建 `src/main/java/com/example/seckill/service/impl/SeckillServiceImplV1Optimistic.java`：

```java
package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.common.ResultCode;
import com.example.seckill.dto.SeckillResponse;
import com.example.seckill.entity.Course;
import com.example.seckill.entity.CourseStock;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.mapper.CourseMapper;
import com.example.seckill.mapper.CourseStockMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.service.SeckillService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Service
@AllArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "seckill.strategy", havingValue = "v1-optimistic")
public class SeckillServiceImplV1Optimistic implements SeckillService {

    private final CourseMapper courseMapper;
    private final CourseStockMapper courseStockMapper;
    private final SeckillOrderMapper orderMapper;

    private String generateOrderNo(Long studentId) {
        return System.currentTimeMillis() + String.format("%04d", studentId % 10000)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    @Override
    @Transactional
    public SeckillResponse seckill(Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(course.getStartTime()) || now.isAfter(course.getEndTime())) {
            throw new BusinessException(ResultCode.CONFLICT, "课程尚未开放选课");
        }

        Long studentId = (Long) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        // ---- 关键改动 1：先原子扣减，再看结果 ----
        int affected = courseStockMapper.tryDecrement(courseId);
        if (affected == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "该课程名额已满");
        }

        // ---- 关键改动 2：扣完再查重；重复时靠事务把库存还回去 ----
        SeckillOrder exist = orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getStudentId, studentId)
                .eq(SeckillOrder::getCourseId, courseId));

        if (exist != null) {
            if (exist.getStatus() == SeckillOrder.STATUS_SUCCESS
                    || exist.getStatus() == SeckillOrder.STATUS_PENDING) {
                // 抛异常 → 事务回滚 → 上面那次扣减自动撤销
                throw new BusinessException(ResultCode.CONFLICT, "你已选过该课程");
            }
            if (exist.getStatus() == SeckillOrder.STATUS_CANCELLED) {
                // R5：退课后重选，把旧单改回成功，不要 INSERT
                exist.setStatus(SeckillOrder.STATUS_SUCCESS);
                exist.setFailReason(null);
                orderMapper.updateById(exist);
                return new SeckillResponse(exist.getOrderNo(), exist.getStatus());
            }
        }

        SeckillOrder order = SeckillOrder.builder()
                .orderNo(generateOrderNo(studentId))
                .studentId(studentId)
                .courseId(courseId)
                .status(SeckillOrder.STATUS_SUCCESS)
                .build();
        // 并发重复点击会在这里撞 uk_student_course
        // → DuplicateKeyException → 事务回滚 → 库存自动还回去
        orderMapper.insert(order);

        return new SeckillResponse(order.getOrderNo(), order.getStatus());
    }

    @Override
    @Transactional
    public SeckillResponse cancel(Long courseId) {
        Long studentId = (Long) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        SeckillOrder order = orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getStudentId, studentId)
                .eq(SeckillOrder::getCourseId, courseId));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "未找到该课程的选课记录");
        }
        if (order.getStatus() != SeckillOrder.STATUS_SUCCESS) {
            throw new BusinessException(ResultCode.CONFLICT, "该记录当前状态无法退课");
        }
        order.setStatus(SeckillOrder.STATUS_CANCELLED);
        orderMapper.updateById(order);

        courseStockMapper.returnStock(courseId);
        return new SeckillResponse(order.getOrderNo(), order.getStatus());
    }
}
```

### 4.3 为什么把「扣库存」挪到了「查重」前面

v0 的顺序是：查重 → 扣库存 → 建单。

v1 改成：**扣库存 → 查重 → 建单**。

理由：查重要先查一次数据库（一次网络往返），如果放在扣减前面，那一次往返里库存可能已经变了。虽然不影响正确性（真正决定成败的是 `affected`），但**先扣减能让"失败得早"**：

- 名额已满的请求（300 并发里的 200 个）在第一次数据库操作后就返回 409，不用再查一次订单表；
- 这 200 个请求的事务极短，连接释放得快，整体吞吐更好。

而"重复选课"这种请求（很少见，只出现在同一个学生并发点击时）多花一次查询无所谓。

**这是性能优化，不是正确性要求**。两种顺序在 `@Transactional` 下都正确。

---

## 5. 实现 B：悲观锁（`v1-pessimistic`）

### 5.1 Mapper 新增查询

```java
/**
 * 加排他锁读取库存行。
 * 必须在一个事务里调用——事务结束（提交或回滚）时锁才释放。
 */
@Select("SELECT * FROM course_stock WHERE course_id = #{courseId} FOR UPDATE")
CourseStock selectForUpdate(@Param("courseId") Long courseId);
```

### 5.2 Service 实现

结构同上，只改扣减那一段：

```java
@Override
@Transactional
public SeckillResponse seckill(Long courseId) {
    // 前面的课程校验、时间窗口校验完全相同

    // ---- 唯一改动：加锁读，然后在锁内判断 + 更新 ----
    CourseStock stock = courseStockMapper.selectForUpdate(courseId);
    if (stock == null) {
        throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
    }
    if (stock.getStock() <= 0) {
        throw new BusinessException(ResultCode.CONFLICT, "该课程名额已满");
    }
    courseStockMapper.absoluteUpdate(courseId, stock.getStock() - 1);
    // 注意：这里用 v0 那条 absoluteUpdate 也没问题了！
    // 因为整行被 FOR UPDATE 锁住，别的线程改不了它。

    // 后面的查重、建单、R5 逻辑完全相同
}
```

### 5.3 一个反直觉的点：`absoluteUpdate` 在悲观锁下是安全的

v0 用 `absoluteUpdate` 是为了**制造** bug，v1 的悲观锁版本用同一条 SQL 却**没有** bug。

区别不在 SQL，在**上下文**：`SELECT ... FOR UPDATE` 已经把这行锁住了，别的线程连读都要等（当前读），所以"我读到的值"在整个事务期间不会变。

**结论**：同一个写法，在有无锁的情况下语义完全不同。**读代码时不能只看那一条 SQL，要看它前后有什么。**

### 5.4 为什么 `FOR UPDATE` 必须在事务里

`SELECT ... FOR UPDATE` 的锁，生命周期是**事务**，不是**语句**。

- 没有事务时，MySQL 处于 autocommit 模式，每条语句自动提交——`SELECT` 一结束锁就释放了。你后面的 `UPDATE` 完全没有保护。
- 有事务时，锁一直持有到 `commit()` 或 `rollback()`。

所以悲观锁版本如果忘了 `@Transactional`，**它和 v0 一样会超卖**，而且更难发现——因为代码里明明写了 `FOR UPDATE`。

这是本阶段最容易踩的坑，没有之一。

### 5.5 悲观锁的真实瓶颈（提前告诉你，免得压测时误判）

300 个并发请求抢同一门课，悲观锁会让它们**完全串行**：

```
请求 1 拿到锁 → 处理 5ms → 提交 → 释放锁
请求 2 拿到锁 → 处理 5ms → 提交 → 释放锁
...
300 个请求 → 约 1.5 秒
```

而且过程会更糟，因为连接池只有 50：

```
50 个连接被占用：
  1 个真的在干活
  49 个在 SELECT FOR UPDATE 上阻塞（占着连接什么都不干）
剩下 250 个请求在等连接（Hikari connection-timeout = 3000ms）
```

**预测**：300 个请求会在 1.5 秒左右全部完成，比 3 秒的连接超时短，所以**应该不会**出现 `Connection is not available` 错误。但 P99 会接近 1.5 秒。

如果实测出现了连接超时，说明我的估算偏乐观——那也是一个很好的数据点，说明「连接池大小」和「锁等待」会互相放大。

**另外记住 `innodb_lock_wait_timeout = 50` 秒**。这意味着如果将来锁等待真的很久，MySQL 会等 50 秒才报错，而 Hikari 的 3 秒会先触发。两个超时的先后顺序，是排查线上问题时必须搞清楚的事。

### 5.6 死锁：本阶段不会遇到，但要知道

悲观锁最危险的问题是死锁。经典场景：

```
事务 A: 锁住课程 1 → 想锁课程 2
事务 B: 锁住课程 2 → 想锁课程 1
→ 互相等待，死锁
```

**本阶段每个请求只锁一行**（一次只抢一门课），所以不会死锁。

但如果将来做「一次抢多门课」或者「扣库存同时扣用户积分」，就**必须约定加锁顺序**（比如永远按 `course_id` 升序加锁）。这是面试必问的点，先记住。

---

## 6. 实现 C：版本号 CAS（`v1-version-cas`，选做）

### 6.1 写法

```java
@Update("UPDATE course_stock SET stock = stock - 1, sold = sold + 1, version = version + 1 " +
        "WHERE course_id = #{courseId} AND version = #{version} AND stock > 0")
int casDecrement(@Param("courseId") Long courseId, @Param("version") Integer version);
```

Service 里要循环重试：

```java
for (int i = 0; i < 3; i++) {
    CourseStock stock = courseStockMapper.selectByCourseId(courseId);
    if (stock == null || stock.getStock() <= 0) {
        throw new BusinessException(ResultCode.CONFLICT, "该课程名额已满");
    }
    int affected = courseStockMapper.casDecrement(courseId, stock.getVersion());
    if (affected == 1) {
        return;  // 抢到了，继续查重建单
    }
    // affected == 0 → 版本被别人改了 → 重试
}
throw new BusinessException(ResultCode.CONFLICT, "系统繁忙，请重试");
```

### 6.2 为什么本场景**不需要**版本号

这是本阶段最有价值的一个认知：

**版本号 CAS 解决的是「读-改-写」里的写丢失。而 `stock = stock - 1 WHERE stock > 0` 已经把读-改-写压成了一条原子语句，天然不存在写丢失，也就没有版本号存在的意义。**

版本号真正有用的场景是：**你的更新逻辑依赖读到的多个字段**。比如「如果库存 > 100 就打 8 折」，折扣依赖于读到的库存值——这时候必须保证"读到的库存"和"写入时库存"一致，才需要 version。

库存扣减是单调递减，不依赖读到的具体值，所以条件更新就够了。

### 6.3 版本 CAS 的真实代价

如果你实现了 C 并压测，会看到：

- **吞吐更低**：每次失败都要重试，重试又要重新 SELECT（两次网络往返）
- **重试风暴**：300 并发抢完 100 个名额后，剩下 200 个请求会重试 3 次才失败，总请求数从 300 变成 ~800
- **P99 更高**：重试的延迟叠加

**这张对比表就是"为什么条件更新优于版本 CAS"的实证**。所以虽然选做，但强烈建议做——它让"选 A 不选 C"从一个主观判断变成一个可量化的结论。

---

## 7. `@Transactional` 的四个坑

v1 才第一次用事务，这四条必须搞清楚。

### 坑 1：默认只对 `RuntimeException` 回滚

Spring 的默认规则是：**`RuntimeException` 及其子类回滚，受检异常（`Exception` 的子类但非 `RuntimeException`）不回滚。**

本项目 `BusinessException extends RuntimeException` —— 这是**故意**的，所以 `throw new BusinessException(...)` 会触发回滚。

**如果当初写成 `extends Exception`**：抛 409 时事务**不会**回滚，库存扣了就扣了，订单没建成 → 每一次"已选过"的重复点击都会泄漏一个名额。而且日志里什么都看不到。

> 检查方法：`javap` 或者直接看 `BusinessException.java` 的 `class` 那一行有没有 `extends RuntimeException`。

### 坑 2：同类内部调用不走代理，事务失效

```java
@Service
public class Foo {
    public void a() {
        b();   // ❌ 直接调用，不经过 Spring 代理，b() 上的 @Transactional 不生效
    }
    @Transactional
    public void b() { ... }
}
```

Spring 的事务是通过**动态代理**实现的：外部调用 `foo.a()` 时拿到的是代理对象，代理在调用前后开/关事务。但类内部 `this.b()` 调用的是原始对象，代理根本没参与。

本项目的 Controller → Service 是外部调用，没问题。但**如果你以后想把 `seckill()` 拆成 `seckill()` + `doDeduct()` 两个方法互相调用，就会踩到**。

### 坑 3：事务方法必须是 `public`

`@Transactional` 加在 `private` / `protected` / 包级方法上**不生效**，而且**不报错**——静默失效，最危险的一类 bug。

### 坑 4：事务不要太长

事务里做 HTTP 调用、发 MQ 消息、`Thread.sleep`，都会让数据库锁/连接被长时间占用。悲观锁版本尤其要注意——事务越长，别人等得越久。

本项目的 `seckill()` 里只有数据库操作，事务时长约 5ms，是合理的。

---

## 8. 文件清单

### 新建

| 文件 | 内容 |
|---|---|
| `src/main/java/com/example/seckill/service/impl/SeckillServiceImplV1Optimistic.java` | 实现 A |
| `src/main/java/com/example/seckill/service/impl/SeckillServiceImplV1Pessimistic.java` | 实现 B |
| `src/main/java/com/example/seckill/service/impl/SeckillServiceImplV1VersionCas.java` | 实现 C（选做） |
| `src/test/java/com/example/seckill/service/impl/SeckillServiceImplV1OptimisticTest.java` | 单元测试 |
| `loadtest/seckill-idempotent.js` | 幂等压测（验证事务回滚真的生效） |
| `docs/压测报告-v1.md` | v1 压测报告 |

### 修改

| 文件 | 改什么 |
|---|---|
| `src/main/java/com/example/seckill/mapper/CourseStockMapper.java` | 加 `tryDecrement` / `selectForUpdate` / `casDecrement` |
| `src/main/resources/application.yaml` | 第 82 行的注释里补上三个新值 |

**注意**：`SeckillServiceImpl`（v0）**一行都不要改**。它要作为对照物长期存在。

---

## 9. 单元测试

新建 `SeckillServiceImplV1OptimisticTest.java`，三个用例：

| 用例 | 打桩 | 断言 |
|---|---|---|
| `seckill_stockEmpty_throws409` | `tryDecrement` 返回 0 | 抛 `BusinessException`，code = 409，message = 「该课程名额已满」 |
| `seckill_success_insertsOrder` | `tryDecrement` 返回 1，`selectOne` 返回 null | 返回的 `status = 1`；`verify(orderMapper).insert(any())` 被调用 1 次 |
| `seckill_duplicate_throws409` | `tryDecrement` 返回 1，`selectOne` 返回一条 `status=1` 的旧单 | 抛 409，message = 「你已选过该课程」 |

模板照抄 `CourseServiceImplTest`：

```java
@ExtendWith(MockitoExtension.class)
class SeckillServiceImplV1OptimisticTest {

    @Mock CourseMapper courseMapper;
    @Mock CourseStockMapper courseStockMapper;
    @Mock SeckillOrderMapper orderMapper;
    @InjectMocks SeckillServiceImplV1Optimistic seckillService;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, Course.class);
        TableInfoHelper.initTableInfo(assistant, CourseStock.class);
        TableInfoHelper.initTableInfo(assistant, SeckillOrder.class);
    }
}
```

**单元测试证不了什么**：`@Transactional` 在 Mockito 单测里不起作用（没有 Spring 上下文，没有真实事务）。所以「抛异常会不会回滚库存」这件事，单测验证不了。

**那怎么验证回滚真的生效？** 靠 §10 的幂等压测。这是本阶段设计上的一个要点：**单元测试证明分支对，压测证明并发对，两者不能互相替代。**

---

## 10. 幂等压测：验证事务回滚真的生效

新建 `loadtest/seckill-idempotent.js`。场景：**1 个学生，10 个并发请求抢同一门课。**

预期：只有 1 个成功，9 个 409「你已选过该课程」，**库存只减 1**。

```javascript
// 关键设计：所有 VU 用同一个 token（同一个学生）
export const options = {
  scenarios: {
    idempotent: {
      executor: 'per-vu-iterations',
      vus: 10,
      iterations: 1,
    },
  },
};

export function setup() {
  const token = loginStudent(studentNo(0));   // 只登录 1 个学生
  return { token: token };
}

export default function (data) {
  const res = seckill(COURSE_ID, data.token);
  // 统计成功/重复
}
```

**判定**：

```sql
-- 抢之前 stock = 100, sold = 0
-- 抢之后必须是：
SELECT stock, sold, stock + sold FROM course_stock WHERE course_id = 88;
-- 期望：stock = 99, sold = 1, 和 = 100
-- 如果 stock = 96 或更小 → 事务回滚没生效，库存泄漏了
```

再加一条：

```sql
SELECT COUNT(*) FROM seckill_order WHERE student_id = 1 AND course_id = 88;
-- 期望：1（唯一索引挡住了 9 条重复插入）
```

**这个测试是整个 v1 里最能证明 `@Transactional` 有用的东西。** 没有它，`@Transactional` 就只是个抄来的注解。

**为什么它会泄漏**：10 个并发请求，每个都执行了 `tryDecrement`（库存减 1，一共减 10），然后 9 个在 `INSERT` 时撞唯一键抛 `DuplicateKeyException`。如果事务生效，那 9 个的 `tryDecrement` 会被回滚，净效果是减 1。如果事务没生效，净效果是减 10 —— 这就是 v0 那种"扣了库存没建成订单"的泄漏，只是规模小而已。

---

## 11. 三组压测对比

压测脚本**完全复用** `loadtest/seckill-single.js`，不用改一行。

切换版本用命令行参数，**不用改 yaml**（避免忘记改回来）：

```powershell
cd D:\software\seckill-course

# A) 乐观锁
.\mvnw.cmd -s D:\develop\apache-maven-3.9.15\conf\settings.xml spring-boot:run `
    "-Dspring-boot.run.arguments=--seckill.strategy=v1-optimistic"

# B) 悲观锁
.\mvnw.cmd -s D:\develop\apache-maven-3.9.15\conf\settings.xml spring-boot:run `
    "-Dspring-boot.run.arguments=--seckill.strategy=v1-pessimistic"

# C) 版本 CAS（选做）
.\mvnw.cmd -s D:\develop\apache-maven-3.9.15\conf\settings.xml spring-boot:run `
    "-Dspring-boot.run.arguments=--seckill.strategy=v1-version-cas"
```

每轮流程：

```powershell
# 1. 预热（避免 Hikari 冷启动的 51 个连接拒绝）
curl.exe -s http://localhost:8080/api/course/1

# 2. 复位
.\loadtest\reset.ps1 -CourseId 88

# 3. 压测
D:\software\_tools\k6\k6-v0.55.0-windows-amd64\k6.exe run `
    -e VUS=300 -e STUDENT_COUNT=300 `
    --summary-trend-stats="avg,min,med,p(90),p(95),p(99),max" `
    loadtest/seckill-single.js

# 4. 判定
.\loadtest\verify.ps1 -CourseId 88
```

**每个版本至少跑 3 轮**，取中位数。单轮数据可能是偶然。

---

## 12. 预期 vs 实测（先写下预测，再填实测）

**这一步不要跳过。** 先写预测、后填实测，才知道自己对机制的理解对不对。如果预测和实测差很远，说明理解有偏差——那个偏差比数字本身更值钱。

| 版本 | 成功单 | 名额已满 | `stock` | `sold` | 缺口 | P95 | P99 |
|---|---|---|---|---|---|---|---|
| v0-baseline（**实测**） | 300 | 0 | 96 | 300 | −296 | 572 ms | 615 ms |
| v1-optimistic **预测** | **100** | **200** | **0** | **100** | **0** | ~500 ms | ~700 ms |
| v1-optimistic 实测 | | | | | | | |
| v1-pessimistic **预测** | **100** | **200** | **0** | **100** | **0** | **~1000 ms** | **~1800 ms** |
| v1-pessimistic 实测 | | | | | | | |
| v1-version-cas **预测** | 100 | 200 | 0 | 100 | 0 | ~800 ms | ~1500 ms |
| v1-version-cas 实测 | | | | | | | |

**乐观锁为什么比 v0 还快一点**：v0 的 300 个请求里，有 200 个会走到 `INSERT` 然后成功——写了 300 条订单。v1 的 200 个失败请求在 `tryDecrement` 就返回了，**少了一次写操作**。所以吞吐可能略好。

**悲观锁为什么慢 3 倍**：完全串行。这不是缺陷，是"用性能换正确性"的明码标价。

---

## 13. 12 项自测清单

每个版本都要过一遍：

- [ ] 1. 用 `--seckill.strategy=v1-optimistic` 启动成功，日志无 ERROR
- [ ] 2. `POST /api/seckill/88` → 200，返回 orderNo 和 status=1
- [ ] 3. 同一学生再抢 → 409「你已选过该课程」
- [ ] 4. 连续抢 100 个不同学生 → 前 100 次 200，第 101 次 409「该课程名额已满」
- [ ] 5. 此时 `stock = 0`、`sold = 100`、`stock + sold = 100`
- [ ] 6. `POST /api/seckill/88/cancel` → 200，`stock = 1`、`sold = 99`
- [ ] 7. **退课后重抢 → 200**（R5，复用原 orderNo，不新建单）
- [ ] 8. 重抢后 `stock = 0`、`sold = 100`，且订单表里仍然只有 1 条记录
- [ ] 9. 重复退课 → 409「该记录当前状态无法退课」
- [ ] 10. 管理员抢课 → 403；管理员退课 → 403
- [ ] 11. 不存在的课程 → 404；`/api/seckill/abc` → 400
- [ ] 12. 换成 `v1-pessimistic` 重启，1~11 全部同样通过

**第 7、8 项最容易翻车**：如果重抢时用了 `INSERT` 而不是 `UPDATE`，会撞 `uk_student_course` → `DuplicateKeyException` → 事务回滚 → 库存还回去了，但学生永远抢不到这门课。

---

## 14. 验收标准

| 项 | 标准 |
|---|---|
| 功能 | §13 的 12 项在乐观锁和悲观锁两个版本下全部通过 |
| 正确性 | 300 VU / 100 名额：`success_orders = 100`、`conservation_gap = 0` |
| 幂等 | 1 学生 10 并发：库存只减 1，订单只 1 条 |
| 三种实现 | 至少有乐观锁和悲观锁两份，能通过配置切换 |
| 数据 | `docs/压测报告-v1.md` 里有三组对比数字，**含预测 vs 实测的差异分析** |
| 代码 | `clean test` 通过，`SeckillServiceImpl`（v0）一行未改 |
| 提交 | `git tag v1-db-lock` |

---

## 15. 如果 v1 还是超卖，按这个顺序排查

不要瞎改，按顺序排除：

| # | 检查 | 如果这里有问题，症状是 |
|---|---|---|
| 1 | `tryDecrement` 的 SQL 里有没有 `AND stock > 0` | 忘了 → 库存变负数（但本表有 CHECK 会报 500） |
| 2 | 是不是还在用 `absoluteUpdate` | affected rows 恒为 1（因为无条件），判断永远通过 |
| 3 | `seckill()` 上有没有 `@Transactional` | 库存泄漏（扣了但订单没建成） |
| 4 | `@Transactional` 是不是加在 `public` 方法上 | 静默失效 |
| 5 | 悲观锁版本是不是忘了 `@Transactional` | `FOR UPDATE` 形同虚设，**和 v0 一样超卖** |
| 6 | 实际加载的是哪个 bean | 用 `--seckill.strategy` 传的值拼写对不对；启动日志里看有没有 `SeckillServiceImplV1Optimistic` 的构造记录 |
| 7 | 压测前有没有复位 | 上一轮的脏数据叠加，缺口算错 |

**最快的确认方法**：在 `seckill()` 第一行加一行日志

```java
log.info("当前策略: v1-optimistic, courseId={}", courseId);
```

启动后看日志有没有打出来。打了说明这个 bean 在跑，没打说明加载的是别人。

---

## 16. 必踩的坑（按踩到的概率排序）

1. **以为是 `@Transactional` 生效了，其实是 Mockito 单测根本没测事务**。单测跑绿不代表回滚对，必须用 §10 的幂等压测验证。
2. **悲观锁忘了 `@Transactional`**，代码里有 `FOR UPDATE` 但完全没作用。
3. **`tryDecrement` 写成 `stock = #{newStock}`**，把 v0 的 bug 原样搬过来。
4. **R5 用 `INSERT` 而不是 `UPDATE`**，退课后永远抢不回来。
5. **strategy 值拼写错误** → 启动报 `No qualifying bean of type 'SeckillService'`。看到这个报错先检查 `havingValue` 和命令行参数是否一致。
6. **两个 `@ConditionalOnProperty` 同时匹配**（比如 v0 的 `matchIfMissing=true` 配了 v1 的值）→ 两个 bean 都加载 → 后加载的覆盖先加载的，行为随机。
7. **压测前忘了预热**，第一轮出现一批 `Connection is not available`，误判为悲观锁的锅。
8. **改完 yaml 忘了重启**，压测的还是上一个版本。

---

## 17. 明确不做

- ❌ 不引入 Redis（v2 才做）
- ❌ 不引入 MQ（v3 才做）
- ❌ 不做分布式锁（单机 MySQL 用不着）
- ❌ 不改 `innodb_lock_wait_timeout`（先观察默认值 50 秒的实际影响）
- ❌ 不调 Hikari 连接池大小（v4 调优阶段才动）
- ❌ 不改表结构（`version` 列已经存在）
- ❌ 不改压测脚本 `seckill-single.js`
- ❌ 不改 v0 的 `SeckillServiceImpl`
- ❌ **不抽公共基类**（理由见 §2）

---

## 18. 面试话术（做完直接用）

> **问：你怎么解决超卖的？**
>
> 我用的是"判断和扣减在同一条 SQL 里"的方案：
>
> ```sql
> UPDATE course_stock SET stock = stock - 1, sold = sold + 1
> WHERE course_id = ? AND stock > 0
> ```
>
> 然后在 Java 里检查 affected rows——返回 1 就是抢到了，返回 0 就是没抢到。
>
> **关键是不要先 SELECT 再判断再 UPDATE**。我第一版就是那样写的，300 并发下 100 个名额卖出了 300 份。因为两个线程同时读到 `stock = 1`，都判断通过，都写回 `stock = 0`，1 个名额卖给 2 个人，而库存显示 0——账面上根本看不出超卖。
>
> 改完之后，同样的压测场景成功单数从 300 降到 100，库存账面 `stock + sold` 也回到了守恒的 100。
>
> **问：为什么不用版本号乐观锁？**
>
> 版本号解决的是"读-改-写"里的写丢失。而 `stock = stock - 1 WHERE stock > 0` 已经把这三步压成了一条原子语句，天然没有写丢失，版本号就没有意义了。
>
> 版本号真正有用的场景是**更新逻辑依赖读到的字段值**，比如"库存大于 100 就打八折"——这时候必须保证读到的库存和写入时的库存一致。库存扣减是单调递减，不依赖读到的值。
>
> 我实测过版本号 CAS：300 并发下流量从 300 个请求放大到约 800 个（失败重试），P99 比条件更新高 2 倍多。
>
> **问：悲观锁呢？**
>
> 也实现了，`SELECT ... FOR UPDATE` 加行锁，在锁内判断和更新。正确性没问题，但**完全串行**——300 个请求排队，P99 从 700ms 涨到 1.8 秒，吞吐掉到单线程水平。
>
> 而且它有个隐藏要求：**必须在事务里**。`FOR UPDATE` 的锁生命周期是事务，不是语句。忘了 `@Transactional` 的话锁立刻释放，代码里有 `FOR UPDATE` 但完全不起作用——这个坑比超卖更难发现。
>
> **问：那生产上选哪个？**
>
> 单机 MySQL 且并发不高的话选条件更新，一行 SQL 解决，没有额外成本。
>
> 但如果像秒杀这种几千 QPS 打一个热门商品的场景，数据库行锁会成为瓶颈——所有请求都要在同一个行锁上排队，MySQL 单行的更新能力大概就几千 TPS。这时候必须把库存判断挪到 Redis 用 Lua 做原子扣减，把数据库请求量砍掉 90% 以上。这是我下一个版本要做的。
