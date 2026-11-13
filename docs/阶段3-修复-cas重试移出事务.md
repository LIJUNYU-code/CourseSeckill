# 阶段 3 修复：把 CAS 的重试移出事务（方案一）

> 适用文件：`src/main/java/com/example/seckill/service/impl/SeckillServiceImplV1VersionCas.java`
> 起因：300 并发只成功 18 单，282 个返回「系统繁忙，请重试」
> 目标：300 并发成功 100 单、超卖 0、缺口 0

---

## 1. 先说清病因（这决定了改法）

你原来的写法是**一个事务里重试 3 次**：

```
@Transactional 开始  ← 此刻 MySQL 给这个事务拍了快照（REPEATABLE-READ）
  for 3 次:
      SELECT version      ← 每次都读同一个快照，version 永远是 0
      UPDATE ... WHERE version = 0
      affected == 0 → 下一轮
@Transactional 结束
```

实测对照（两个真实会话）：

| 时刻 | 会话 A | 会话 B | A 读到 |
|---|---|---|---|
| 1 | `SELECT stock, version` | | `100, 0` |
| 2 | | `UPDATE SET version=1` | |
| 3 | **同一事务内**再 `SELECT` | | **`100, 0`** ❌ 旧值 |
| 4 | 改 `SELECT ... FOR UPDATE` | | `99, 1` ✅ |

**所以 3 次重试 = 用同一个过期答案交 3 次卷。** 不是重试次数不够，是重试拿不到新数据。

验证：把全局隔离级别临时改成 `READ-COMMITTED`（每次读都拿最新已提交版本），
同样代码、同样 300 VU 立刻变成 100 单 / 0 超卖 / 0 缺口。
说明业务逻辑本身是对的，**错在重试与事务的边界没对齐**。

结论：**每次重试必须是新事务（= 新快照）。**

---

## 2. 改法：外层循环无事务，内层每次尝试一个事务

### 2.1 为什么不用「拆成两个类 + 互相注入」

那样也可以，但要新建一个类、还要绕开 Spring 代理。本阶段有更直接的工具：
`TransactionTemplate`——**手动开事务**，边界写在哪一眼就能看见，不存在自调用不走代理的问题。

### 2.2 目标结构

```java
public SeckillResponse seckill(Long courseId) {      // ← 没有 @Transactional
    校验课程、时间窗口

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        // 每一次 execute = 一个新事务 = 一个新快照
        SeckillResponse resp = transactionTemplate.execute(status -> tryOnce(courseId, studentId));
        if (resp != null) return resp;               // 抢到了
        // resp == null 表示版本冲突（本次没写任何数据，空事务提交无害）
        sleep 一点点，避免同时重试继续撞
    }
    throw new BusinessException(ResultCode.CONFLICT, "系统繁忙，请重试");
}
```

### 2.3 内层 `tryOnce` 的返回值约定

| 情况 | 内层怎么做 | 外层怎么反应 |
|---|---|---|
| 扣减成功、建单成功 | 返回 `SeckillResponse` | 直接返回给调用方 |
| 版本冲突 `affected == 0` | **返回 `null`**（什么都没写，空事务无害） | 重试下一轮 |
| 名额已满 `stock <= 0` | 抛 `BusinessException` 409「该课程名额已满」 | **不重试**，直接抛出 |
| 已选过 / 退课后重选 | 与现在一致 | **不重试**，直接抛出或返回 |

> 关键点：**只有「版本冲突」返回 null 让外层重试**。其它业务失败（名额满、已选过）
> 必须原样抛出，绝不能落进重试分支——否则 200 个「名额已满」的请求会各跑 3 次，
> 数据库白挨 600 次查询。

---

## 3. 代码骨架（照这个改，别整段复制粘贴，看懂再写）

### 3.1 新增字段

```java
private final TransactionTemplate transactionTemplate;

private static final int MAX_ATTEMPTS = 3;
private static final long RETRY_BACKOFF_MS = 5L;   // 见 3.4

private String generateOrderNo(Long studentId) { ... }   // 保持不变
```

`@AllArgsConstructor` 会自动把它加进构造器，不用手写。

> ⚠️ Spring Boot 会**自动配置** `TransactionTemplate` bean（条件：容器里只有一个
> `PlatformTransactionManager`）。如果启动报 `No qualifying bean of type
> 'org.springframework.transaction.support.TransactionTemplate'`，就在配置类里补一个：
> ```java
> @Bean
> public TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
>     return new TransactionTemplate(tm);
> }
> ```

### 3.2 外层 `seckill`

```java
@Override
public SeckillResponse seckill(Long courseId) {          // 注意：去掉了 @Transactional
    Course course = courseMapper.selectById(courseId);
    if (course == null) throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");

    LocalDateTime now = LocalDateTime.now();
    if (now.isBefore(course.getStartTime()) || now.isAfter(course.getEndTime()))
        throw new BusinessException(ResultCode.CONFLICT, "课程尚未开放");

    Long studentId = (Long) SecurityContextHolder.getContext()
            .getAuthentication().getPrincipal();

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        SeckillResponse resp = transactionTemplate.execute(status -> tryOnce(courseId, studentId));
        if (resp != null) {
            return resp;
        }
        log.debug("CAS 版本冲突，第 {} 次重试 courseId={} studentId={}", attempt, courseId, studentId);
        sleepQuietly(RETRY_BACKOFF_MS);
    }
    throw new BusinessException(ResultCode.CONFLICT, "系统繁忙，请重试");
}
```

**注意**：`tryOnce` 里抛出的 `BusinessException` 会穿过 `transactionTemplate.execute`
直接冒到调用方，不会被循环吞掉——这正是我们要的。

### 3.3 内层 `tryOnce`（原 `seckill` 的主体，删掉循环）

```java
private SeckillResponse tryOnce(Long courseId, Long studentId) {
    CourseStock stock = courseStockMapper.selectByCourseId(courseId);
    if (stock == null || stock.getStock() <= 0) {
        throw new BusinessException(ResultCode.CONFLICT, "该课程名额已满");   // 不重试
    }
    int affected = courseStockMapper.casDecrement(courseId, stock.getVersion());
    if (affected == 0) {
        return null;                     // 版本冲突 → 外层重试（本事务没写任何东西）
    }

    SeckillOrder exist = orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
            .eq(SeckillOrder::getStudentId, studentId)
            .eq(SeckillOrder::getCourseId, courseId));

    if (exist != null) {
        if (exist.getStatus() == SeckillOrder.STATUS_SUCCESS
                || exist.getStatus() == SeckillOrder.STATUS_PENDING) {
            throw new BusinessException(ResultCode.CONFLICT, "你已选过该课程");
        }
        if (exist.getStatus() == SeckillOrder.STATUS_CANCELLED) {
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
    orderMapper.insert(order);           // 撞 uk_student_course 会抛 DuplicateKeyException

    return new SeckillResponse(order.getOrderNo(), order.getStatus());
}
```

> ⚠️ 这里**必须**用 `courseStockMapper.selectByCourseId`（普通快照读），
> **不要**改成 `selectForUpdate`。一加 `FOR UPDATE` 就是悲观锁了，
> 三个实现的对比立刻失去意义。

### 3.4 那 5 毫秒的 backoff 是干什么的

300 个请求同时重试，会 3 波一起撞。加一点点随机抖动能把它们错开：

```java
private void sleepQuietly(long ms) {
    try {
        Thread.sleep(ThreadLocalRandom.current().nextLong(ms, ms * 2));
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();      // 恢复中断标志，别吞掉
    }
}
```

`ThreadLocalRandom.nextLong(5, 10)` 让每个线程等 5~9ms，比固定 5ms 更容易散开。
`InterruptedException` 必须 `interrupt()` 回去——这是我们第 4 次强调这件事。

### 3.5 `cancel()` 不要动

`cancel` 只有一次写、没有重试，`@Transactional` 保持原样。

---

## 4. 顺手修两个小问题

### 4.1 `loadtest/reset.ps1` 少了 version

现在：
```sql
UPDATE seckill.course_stock SET stock = capacity, sold = 0 WHERE course_id = $CourseId;
```
改成：
```sql
UPDATE seckill.course_stock SET stock = capacity, sold = 0, version = 0 WHERE course_id = $CourseId;
```

理由：不影响 CAS 正确性（比的是读到的值不是固定值），但跨轮次数据不干净，
写报告时会被自己的脏数据绊一下。实测跑完一轮后 `version=28` 而成功单只有 10。

### 4.2 变量名

你已经删掉了 `acquired`，这次重构成 `tryOnce` 返回 `null` 的写法之后，
不会再出现「名字反了」的问题——但请确认旧代码里那个 `boolean acquired` 和
`if (i == 2) acquired = true;` 已经整段被替换掉了，不要留残骸。

---

## 5. 验证清单

### 5.1 编译 + 启动

```powershell
cd D:\software\seckill-course
& .\mvnw.cmd "-s" "D:\develop\apache-maven-3.9.15\conf\settings.xml" -B -ntp -o `
  "-Daether.syncContext.named.factory=noop" clean compile
```
看 `BUILD SUCCESS`（不要看 exit code，Lombok 的 warning 会让它等于 1）。

启动时**必须确认隔离级别还是 REPEATABLE-READ**——我临时改过全局，现在已还原：
```sql
SELECT @@global.transaction_isolation;   -- 期望 REPEATABLE-READ
```

> 这是本次验证的核心：**在默认隔离级别下**也要 100 单。
> 如果谁把数据库改成 READ-COMMITTED 才跑通，那等于什么都没修。

### 5.2 单次抢课（防双扣回归）

```powershell
# 复位
& .\loadtest\reset.ps1 -CourseId 88
# 学生1 抢一次
# 期望：stock=99 sold=1 version=1 stock+sold=100
```

`version` 必须恰好等于成功单数——这是 CAS 特有的额外校验，另两个实现没有。

### 5.3 300 并发（主验证）

```powershell
& .\loadtest\reset.ps1 -CourseId 88
& "D:\software\_tools\k6\k6-v0.55.0-windows-amd64\k6.exe" run `
  -e VUS=300 -e STUDENT_COUNT=300 `
  --summary-trend-stats="avg,med,p(95),p(99),max" loadtest/seckill-single.js
& .\loadtest\verify.ps1 -CourseId 88
```

**期望**：

| 指标 | 期望值 | 现在（坏） |
|---|---|---|
| `biz_success` | **100** | 18 |
| `biz_sold_out` | **200** | 0 |
| `biz_other` | **0** | 282 |
| `success_orders` | **100** | 18 |
| `oversold_by` | **0** | −82 |
| `conservation_gap` | **0** | 0 |
| `version` | **100** | 28 |
| `p95` | 500~700ms | 516ms |

连跑 **3 轮**，每轮都必须是 100 单。偶发 99 单说明 backoff 不够，偶发 101 单说明
CAS 坏了（这个组合不可能出现，出现就是有别的 bug）。

### 5.4 反向验证（证明测试有牙齿）

把 `MAX_ATTEMPTS` 改成 `1`，再跑 300 VU：
- 期望 `biz_success` 明显低于 100、`biz_other` 大量上升
- 说明「重试次数」这个变量确实在起作用
- 改回 `3`

### 5.5 幂等（1 学生 10 并发）

同一学生 10 个并发抢同一门课：
- 期望：1 个 200 + 9 个 409「你已选过该课程」
- 期望：`stock=99 sold=1`、订单 1 条
- 这条验证的是「事务回滚」——9 个失败请求扣掉的库存必须自动还回来

---

## 6. 三个必踩的坑

1. **外层忘了去掉 `@Transactional`**
   如果 `seckill()` 上还留着 `@Transactional`，`transactionTemplate.execute` 会加入
   外层事务（`PROPAGATION_REQUIRED` 默认行为）→ 还是同一个快照 → **症状和没改一样**。
   必须让外层完全无事务。改完确认方法上没有注解。

2. **`tryOnce` 写成 `public` 并加 `@Transactional`**
   放进同一个类里就是自调用，注解不生效（任务书 §7 坑 2）。要么像上面这样
   用 `private` + 纯 `TransactionTemplate`，要么把 `tryOnce` 挪到另一个 bean。
   **不要两个都加**——会让人误以为事务是注解开的。

3. **`InterruptedException` 吞掉**
   `catch (InterruptedException e) { }` 会让线程池里的中断信号丢失，
   后续 `Stop-Process` 杀不掉、Tomcat 关不掉。必须 `Thread.currentThread().interrupt()`。

---

## 7. 这一课的收获（面试可直接讲）

**「乐观锁的重试边界必须和事务边界对齐。」**

- 事务内重试 → 快照冻结 → 重试用的是同一个过期版本号 → 永远失败
- 跨事务重试 → 每次新快照 → 正常

这不是 MySQL 的 bug，是 MVCC 的设计：一个事务内多次读必须看到一致的数据，
否则同一事务里的两个查询会互相矛盾。理解这一点，就知道「重试」天然属于
**事务之外**的职责——它管理的是「这个事务要不要重来」，不是「事务内的某条 SQL 要不要重来」。

配套的一句话：**乐观锁在高竞争下会退化成重试风暴。** 300 并发抢 100 个名额意味着
200 个请求注定失败，而它们每失败一次都会重试 3 次 → 数据库要处理 300 + 200×3 = 900 次
CAS 尝试。所以真实系统里，乐观锁前面通常还有一层限流或 Redis 预扣，
这就是 v2 要做的事。
