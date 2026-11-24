# 阶段 6 任务书：v5-sharding（分库分表）

> 这份任务书基于 **2026-11-24 的实测**，不是查文档抄来的。
> 文中每一个"报错原文"、每一个"Com_update 增量"都是现场跑出来的。
> 带着 ⚠️ 的段落是**预期与实测不符**的地方，比顺利的部分更值得读。

---

## 0. 这一版要回答什么

### 0.1 靶子

v4 结束后你的系统状态是：

| 版本 | P95 | 成功单 | 超卖 |
|---|---|---|---|
| v0-baseline | 572ms | 300/100 | +200 |
| v1-db-lock | 339ms | 100/100 | 0 |
| v2-redis-lua | 322ms | 100/100 | 0 |
| v3-mq-async | 66ms | 100/100 | 0 |

v3 之后，请求路径上已经没有明显的性能问题了。那 v5 分表要解决什么？

**诚实的回答：v5 在你的本地环境里，解决的不是性能问题。**

单表 10000 订单、100 课程的规模，分不分表对性能没有可测量的影响。实测数据显示：跨分片 `ORDER BY id DESC LIMIT offset,10`，offset 从 0 到 1500，耗时全部 6~7ms。

那这一版做什么？**做三件事**：

1. **验证机制**：分片路由、跨分片聚合、全局唯一键失效——这些是"知道"和"做过"的区别。
2. **量化代价**：分表到底让你多花了多少数据库操作——这个可以用 `Com_update` 精确测出来（见 §3.2，这是本阶段最有价值的数字）。
3. **写清边界**：什么规模下分表值得、什么规模下不值得。

### 0.2 最重要的一句话

> **分表解决的是"数据量大"，你的项目核心矛盾是"并发高"。这是两个不同的问题。**

你的 10000 个订单，分不分表都是 6ms。但如果某天涨到 2000 万订单，单表查询会慢到无法接受——那时才需要分表。

所以 v5 的产出不是"更快了"，而是：

- 一份能跑的 4 分片实现
- 一张精确的代价表（多打了多少次 UPDATE）
- 一段能讲清楚的判断标准（什么规模该分）

**如果你在面试里说"我做了分库分表让 QPS 提升了 3 倍"，面试官一问数据量就会露馅。说"我量化了分表的代价，并验证了它解决了全局唯一索引、深分页这两个真问题"，才站得住。**

### 0.3 成功标准

| # | 标准 | 怎么验 |
|---|---|---|
| A1 | 4 个分片能正确路由 | `student_id=1..8` 分别落到 `_1/_2/_3/_0/_1/_2/_3/_0` |
| A2 | 现有 v3 链路分表后仍然正确 | 抢课→落单→退课→重选 全部走通 |
| A3 | 全局唯一索引失效有实测证据 | 同一 `order_no` 在两张物理表同时存在 |
| A4 | 无分片键的 UPDATE 广播代价被量化 | `Com_update` 增量 = 分片数 |
| A5 | 热 SQL 改造后能只打一个分片 | 带 `student_id` 的 UPDATE `Com_update` 增量 = 1 |
| A6 | 分表前后 P95 对比有数据 | 复用 v4 的容量扫描脚本，两个数并列 |
| A7 | 明确写出"本地测不出什么" | §9.3 的空白清单不能空 |

---

## 1. 前置事实（全部实测）

### 1.1 为什么选 ShardingSphere-JDBC 而不是 Proxy

| | JDBC（驱动） | Proxy（独立进程） |
|---|---|---|
| 部署形态 | jar 依赖，和你的应用同一个进程 | 独立服务，应用把它当 MySQL 连 |
| 本机可行性 | **可行** | 又要一个进程 + 一份内存 |
| 改代码 | 换 DataSource 即可 | 一行不用改 |
| 你能学到的 | SQL 改写、路由、归并算法 | 部署运维 |
| 你现在的内存 | 可用 1~5 GB，波动大 | +300~500MB 常驻 |

选 JDBC。理由：**内存不够**。Proxy 要额外跑一个 JVM，你的机器上 IDEA 一个就吃 3.1 GB，再开 Proxy，压测时测出来的全是内存换页。

### 1.2 Boot 4 兼容性：兼容，但不是"加上依赖就能用"

先说好消息：`org.apache.shardingsphere:shardingsphere-jdbc:5.5.3` 的 pom 里**没有任何 Spring 依赖**，它是纯 JDBC 驱动。所以 Boot 4 和其它 Spring 版本的兼容性问题**不存在**——那些问题只发生在已经停更的 `shardingsphere-jdbc-core-spring-boot-starter` 上（它停在 5.2.1）。

用法：把 `spring.datasource.url` 改成 `jdbc:shardingsphere:classpath:sharding.yaml`，其余不变。

### 1.3 🔴 5.5.x 把模块拆成 test scope，必须手动补齐 5 个

这是本阶段第一个坑，也是最容易被劝退的地方。

`shardingsphere-jdbc:5.5.3` 的 pom 里把 8 个模块标成了 `<scope>test</scope>`，所以**不会传递给你**。你必须自己显式声明：

```xml
<properties>
    <ss.version>5.5.3</ss.version>
</properties>

<!-- 分片驱动本体 -->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc</artifactId>
    <version>${ss.version}</version>
</dependency>
<!-- 1. 分片规则引擎：没有它 !SHARDING 这个标签无人识别 -->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-sharding-core</artifactId>
    <version>${ss.version}</version>
</dependency>
<!-- 2. HikariCP 的键名映射：没有它 jdbcUrl 会变成 null -->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-infra-data-source-pool-hikari</artifactId>
    <version>${ss.version}</version>
</dependency>
<!-- 3. 独立模式上下文：没有它启动就崩 -->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-standalone-mode-core</artifactId>
    <version>${ss.version}</version>
</dependency>
<!-- 4. 内存元数据仓库：配了 standalone-mode-core 之后通常还需要它 -->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-standalone-mode-repository-memory</artifactId>
    <version>${ss.version}</version>
</dependency>
<!-- 5. 权限 SPI：没有它连第一条 SQL 都发不出去 -->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-authority-simple</artifactId>
    <version>${ss.version}</version>
</dependency>
<!-- 6. MySQL SQL 解析器 -->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-parser-sql-engine-mysql</artifactId>
    <version>${ss.version}</version>
</dependency>
```

#### 逐个漏掉的报错原文（全部实测，非查文档）

| 缺哪个 | 报错原文 | 崩在哪 |
|---|---|---|
| `sharding-core` | `Can't construct a java object for !SHARDING; exception=Invalid tag: !SHARDING` | 解析 yaml 时 |
| `infra-data-source-pool-hikari` | `java.lang.NullPointerException: Cannot invoke "Object.toString()" because the return value of "java.util.Map.get(Object)" is null` | `StorageUnit.<init>(StorageUnit.java:52)` |
| `standalone-mode-core` | `SPI-00001: No implementation class load from SPI 'org.apache.shardingsphere.mode.manager.builder.ContextManagerBuilder' with type 'null'` | `ShardingSphereDataSource.createContextManager(ShardingSphereDataSource.java:77)` |
| `authority-simple` | `SPI-00001: No implementation class load from SPI 'org.apache.shardingsphere.authority.spi.PrivilegeProvider' with type 'ALL_PERMITTED'` | `AuthorityRule.<init>(AuthorityRule.java:51)` |
| `HikariCP`（你那有 starter-jdbc，不用管） | `java.lang.ClassNotFoundException: com.zaxxer.hikari.HikariDataSource` | `DataSourcePoolCreator.create(...)` |

**为什么第 2 个特别难查**：报错是个 `NullPointerException`，而且栈指向 `StorageUnit` 的构造函数——完全看不出是"缺了个池子模块"。

根因是：yaml 里你写的是 Hikari 的原生键 `jdbcUrl`，但 ShardingSphere 内部要取标准键 `url`。这个键名翻译是由 `infra-data-source-pool-hikari` 里的 SPI 实现注册的，`infra-data-source-pool-core` 只有抽象类没有映射。**所以这个 `NullPointerException` 实际含义是"我不知道怎么把 jdbcUrl 翻译成 url"。**

#### 知识点

- ShardingSphere **5.4 及之前**：`shardingsphere-jdbc-core-spring-boot-starter` 一次带齐所有模块。
- ShardingSphere **5.5 起**：改成"驱动 + 你自己挑模块"，pom 里标 `test` 是因为它们只在 ShardingSphere 自己的单测里用。
- **这是一次不兼容的架构调整，官方文档的 "Quick Start" 还写着旧的 starter 用法的视频/文章都过期了。**

### 1.4 ⚠️ 本地数据规模：能测什么，测不出什么

| 项目 | 现在的量 | 分表有意义的门槛 | 结论 |
|---|---|---|---|
| `seckill_order` | 0~10000 行 | 500 万~2000 万行 | **测不出性能收益** |
| `course_stock` | 100 行 | 永远不需要分 | 不分 |
| `student` | 10000 行 | 不在这版范围 | 不分 |
| `course` | 100 行 | 永远不需要分 | 不分 |
| `mq_consume_log` | 0~10000 行 | 可以顺带分，但没意义 | 不分 |

**实测证据**：2000 行 / 4 分片时，`ORDER BY id DESC LIMIT offset,10`：

| offset | 耗时 |
|---|---|
| 0（首次含预热） | 81ms |
| 100 | 7ms |
| 500 | 7ms |
| 1000 | 6ms |
| 1500 | 6ms |

**任务书原计划里"深分页 15ms→60ms"的预期，在这个数据规模下完全无法复现。** 不要为了凑数去伪造。

### 1.5 环境清单（2026-11-24 实测）

| 项 | 值 |
|---|---|
| 平台 | Windows 11 家庭版 Build 26200 |
| CPU | Intel i7-14650HX，16 核 24 线程 |
| 内存 | 总 15.61 GB，可用 1.2~5.6 GB（波动大） |
| JDK | 26.0.1（`JAVA_HOME=C:\Program Files\Java\jdk-26.0.1`） |
| Maven | 3.9.15（必须指定 `-s D:\develop\apache-maven-3.9.15\conf\settings.xml`） |
| Spring Boot | 4.1.1 |
| MyBatis-Plus | 3.5.17（`mybatis-plus-spring-boot4-starter`） |
| MySQL | 8.0.45 |
| Redis | Memurai 8.2.10，`D:\tools\Redis\memurai.exe` |
| RabbitMQ | 3.13.7 in Docker（`seckill-rabbitmq`，限内存 512MB / 0.5 核） |
| k6 | v0.55.0 |
| ShardingSphere | **5.5.3**（最新；共 17 个版本，4.x 已停） |

---

## 2. 分片设计

### 2.1 分片键：为什么是 `student_id`

分片键的选择只有一个原则：**让最高频的查询能定位到单个分片。**

你的流量构成（按调用次数排序，从 v3 的代码推出来）：

| SQL | 频率 | 带 `student_id` 吗 | 带 `course_id` 吗 |
|---|---|---|---|
| `GET /api/order/my` → `WHERE student_id = ?` | 高 | ✅ | ❌ |
| `POST /api/seckill/{id}` → 查重 `WHERE student_id=? AND course_id=?` | 高 | ✅ | ✅ |
| 消费者 `updateStatusByOrderNo` → `WHERE order_no = ?` | 高 | ❌ | ❌ |
| 退课 `updateStatusIfMatch` → `WHERE id = ?` | 中 | ❌ | ❌ |
| `updateStock` → `WHERE course_id = ?` | 高 | ❌ | ✅ |

`student_id` 覆盖了前两项（最高频的读 + 查重）。**所以分片键选 `student_id`。**

但注意最后三行——**它们都不带 `student_id`**。这就是 §3.2 要解决的致命问题。

### 2.2 分几片：4 片

| 片数 | 好处 | 坏处 |
|---|---|---|
| 2 | 最简单 | 效果差 |
| **4** | **够看清机制，表名好记** | 扩容要迁移 |
| 8 | 更接近真实 | 配置更啰嗦，本地测不出差别 |
| 16 | 真实生产常用 | **扩容时 %16 全要重算，必须翻倍迁移** |

选 4 的理由：
1. 本地数据量下，4 和 16 的性能差别测不出来，4 更容易看懂每一行落在哪。
2. 后面讲"扩容为什么这么痛"时，`%4 → %8` 要迁移一半数据，比 `%16 → %32` 好算。
3. **不要选 16**——真实生产里 16 片之后想扩到 32，`student_id % 16` 和 `% 32` 的结果对不上，全量重分布。要么一次到位选 1024，要么用一致性哈希。

### 2.3 为什么不用 `course_id` 做分片键

看起来有诱惑力：抢课是热点，按课程分片能让同一门课的所有请求落在同一个分片。

但两个致命问题：

1. **`GET /api/order/my` 会退化成全分片扫描**。学生查自己的订单，被分散在全部 4 个分片里。
2. **热门课程依然是单点**。课程 88 有 10 万人抢，它还是落在一个分片上，分片救不了它。

真正的热门课程问题要靠**缓存 + 队列**（你 v2、v3 已经做了），不是靠分表。

### 2.4 实际数据分布（实测）

4 张物理表，`student_id = 1..8` 的结果：

| student_id | 落到的表 | 计算 |
|---|---|---|
| 1 | `seckill_order_1` | `1 % 4 = 1` |
| 2 | `seckill_order_2` | `2 % 4 = 2` |
| 3 | `seckill_order_3` | `3 % 4 = 3` |
| 4 | `seckill_order_0` | `4 % 4 = 0` |
| 5 | `seckill_order_1` | `5 % 4 = 1` |
| 6 | `seckill_order_2` | |
| 7 | `seckill_order_3` | |
| 8 | `seckill_order_0` | |

**完全正确。这是分表能跑通的最低要求，也是唯一能立刻看到的东西。**

---

## 3. 🔴 三个会坏掉的东西（全部实测）

这一节是本阶段最有价值的部分。**分表能跑通只是入场券，真正的知识在这三个坑里。**

### 3.1 `uk_order_no` 跨分片完全失效

#### 现象

`seckill_order` 的物理表上有 `UNIQUE KEY uk_order_no (order_no)`。分表后：

```
向 student_id=11 插入 order_no='SAME-NO'  → 成功，落在 seckill_order_3
向 student_id=12 插入 order_no='SAME-NO'  → 成功，落在 seckill_order_0
```

查两张表：

```
seckill_order_0 : order_no='SAME-NO' 的行数 = 1
seckill_order_1 : 0
seckill_order_2 : 0
seckill_order_3 : order_no='SAME-NO' 的行数 = 1
```

**同一个订单号，在两张物理表里各存在一份。**

#### 为什么

`UNIQUE KEY` 是**表级**约束，不是**库级**约束。4 个分片就是 4 张独立的表，每张表的唯一索引各管各的。

#### 影响

你的业务代码里有一句注释写着"`uk_order_no` 是唯一真正的约束"——**分表后这句话不再成立**。

具体后果：
- `GET /api/order/{orderNo}` 用 `WHERE order_no = ?` 查询，如果两条记录都在，`selectOne` 会抛 `TooManyResultsException`。
- 雪花 ID 生成重复的概率极低（同一毫秒 + 同机器 + 同序列号冲突才会重复），但**"极低"不等于"有保证"**。

#### 应对

**必须靠应用层保证，最可靠的是雪花 ID**（时间戳 + 机器号 + 序列号，19 位 Long，本地生成不依赖数据库）。任务书 §4.3 讲细节。

### 3.2 🔴 无分片键的 UPDATE 一律广播到所有分片

**这是 v5 最关键的一节。如果你只记一件事，记这个。**

#### 实测方法

用 MySQL 自己的计数器 `SHOW GLOBAL STATUS LIKE 'Com_update'`。**这是数据库自己记的账，不会骗人。**

4 个分片，每张表 1 行数据。

| 场景 | SQL | `Com_update` 增量 | `affected rows` |
|---|---|---|---|
| 1 | `UPDATE seckill_order SET status=1 WHERE order_no='NO-2' AND status=0` | **4** | 1 |
| 2 | `UPDATE seckill_order SET status=1 WHERE id=3 AND status=0` | **4** | 0 |
| 3（对照） | `UPDATE seckill_order SET status=1 WHERE student_id=4 AND order_no='NO-4' AND status=0` | **1** | 1 |
| 4（SELECT） | `SELECT ... WHERE order_no='NO-1'` | `Com_select` **+2** | 1 行 |

#### 怎么读这张表

**场景 1**：`order_no` 不是分片键，ShardingSphere 无法算出该打哪张表，于是**把这条 UPDATE 改写后发往全部 4 个分片**，每个分片执行一次。4 个分片里只有 1 个真正改到了行（`affected=1`），另外 3 个执行了但 0 行受影响。

**场景 2 更反直觉**：`WHERE id = 3`，`id` 是主键，**但不是分片键**。结果同样是广播 4 次，而且 `affected = 0`——因为 id=3 那行在 `seckill_order_2`，而这次 update 的目标是……不管了，反正 4 个分片都跑了一遍，都没命中。

**场景 3 是对照组**：带上 `student_id`，`Com_update` 只增加 1。**只有 1 个分片被访问。**

#### 对你现在代码的精确影响

你 v3 里每条消息的消费过程要跑 2 条 UPDATE：

```java
// SeckillOrderConsumer.handle()
int affected = courseStockMapper.tryDecrement(message.courseId());      // 普通表，1 次
int rows = seckillOrderMapper.updateStatusByOrderNo(orderNo, STATUS_SUCCESS); // 分片表，4 次！
```

100 单 = 100 × (1 + 4) = **500 次 UPDATE**，而不分表时只要 100 × 2 = 200 次。

**翻 2.5 倍。**

再加上退课：`updateStatusIfMatch(id, ...)` 同样是广播 4 次。

#### 这会毁掉 v3 的 66ms 吗

**会。** 但要诚实：v3 的 66ms 是 HTTP 响应时间，消费者在后台跑，不阻塞 HTTP 响应。所以：

- **HTTP P95 不会变差**（消息还是异步的）
- **消费吞吐会下降**，队列排空时间变长
- **数据库写入压力翻 2.5 倍**

`capacity-scan-v4.ps1` 里有一个 `排空` 列专门测这个，分表前后对比就能看出来。

#### 应对：§4.2

### 3.3 未写进配置的表，直接"不存在"

#### 现象

只配了 `seckill_order` 分片，没配 `course_stock`，启动后：

```
Table or view 'course_stock' does not exist.
```

**不是权限问题，不是库选错了，是 ShardingSphere 的元数据里根本没有这张表。**

#### 为什么

ShardingSphere 拿到 `jdbc:shardingsphere:...` 连接后，**它自己就是数据库**。你所有的 SQL 都先过它的解析器。它只认识 yaml 里登记过的逻辑表。没登记的，解析时直接报"表不存在"。

这跟"数据库里有这张表"是两件事——**ShardingSphere 是代理层，不是透明转发**。

#### 应对：`!SINGLE` 规则

```yaml
rules:
  - !SHARDING
    tables:
      seckill_order: ...
  # 把普通表登记进来
  - !SINGLE
    tables:
      - ds_0.course_stock
      - ds_0.mq_consume_log
```

#### 实测验证（加了 `!SINGLE` 之后）

| 操作 | 结果 |
|---|---|
| `SELECT course_id, stock, sold FROM course_stock` | 读到 2 行，值正确 |
| `UPDATE course_stock SET stock=stock-1, sold=sold+1 WHERE course_id=? AND stock>0` | `affected = 1` ✅ 这是 v3 最热的那条 SQL |
| `INSERT INTO mq_consume_log (msg_id, status) VALUES (?, ?)` （AUTO_INCREMENT 主键） | `affected = 1`，总行数 1 ✅ |
| 同一事务里改 `course_stock` + 插 `seckill_order`，然后回滚 | `course_stock` 回到 99/1，`seckill_order` 0 行 ✅ **跨表事务能被整体撤销** |

**第四条很重要**：ShardingSphere 包装的 DataSource，和 Spring 的 `TransactionTemplate` / `@Transactional` 能共存。**你的业务代码不用改事务逻辑。**

---

## 4. 应对方案

### 4.1 普通表：全部用 `!SINGLE` 登记

要登记的 5 张表：

```yaml
  - !SINGLE
    tables:
      - ds_0.student
      - ds_0.course
      - ds_0.course_stock
      - ds_0.sys_user
      - ds_0.mq_consume_log
```

**5 张表一行都不能漏**。漏了哪张，哪张的接口就会报 `Table or view 'xxx' does not exist`。

### 4.2 两条热 UPDATE 必须加 `student_id`

这是**唯一能让分表不拖慢系统的路子**。改动很小，但必须改。

#### 改造 1：消费者改订单状态

**现在**（`SeckillOrderMapper.java:25-28`）：

```java
@Update("UPDATE seckill_order SET status = #{newStatus} " +
        "WHERE order_no = #{orderNo} AND status = 0")
int updateStatusByOrderNo(@Param("orderNo") String orderNo,
                          @Param("newStatus") Integer newStatus);
```

**改成**：

```java
@Update("UPDATE seckill_order SET status = #{newStatus} " +
        "WHERE student_id = #{studentId} AND order_no = #{orderNo} AND status = 0")
int updateStatusByOrderNo(@Param("studentId") Long studentId,
                          @Param("orderNo") String orderNo,
                          @Param("newStatus") Integer newStatus);
```

**好消息**：`SeckillMessage` 里已经有 `studentId` 了（`record SeckillMessage(String msgId, String orderNo, Long studentId, Long courseId)`），消费者手里就有，**不用改消息体**。

调用点：`SeckillOrderConsumer.java:76-77`，改成 `message.studentId()`。

同样要改的还有 `updateStatusAndFailReason`（`SeckillOrderMapper.java:33-37`，消费者在库存不足时用它）：

```java
@Update("UPDATE seckill_order SET status = #{newStatus}, fail_reason = #{failReason} " +
        "WHERE student_id = #{studentId} AND order_no = #{orderNo} AND status = 0")
int updateStatusAndFailReason(@Param("studentId") Long studentId,
                              @Param("orderNo") String orderNo,
                              @Param("newStatus") Integer newStatus,
                              @Param("failReason") String failReason);
```

#### 改造 2：退课改订单状态

**现在**（`SeckillOrderMapper.java:15-19`）：

```java
@Update("UPDATE seckill_order SET status = #{newStatus} " +
        "WHERE id = #{id} AND status = #{expectStatus}")
int updateStatusIfMatch(@Param("id") Long id,
                        @Param("newStatus") Integer newStatus,
                        @Param("expectStatus") Integer expectStatus);
```

**改成**：

```java
@Update("UPDATE seckill_order SET status = #{newStatus} " +
        "WHERE student_id = #{studentId} AND id = #{id} AND status = #{expectStatus}")
int updateStatusIfMatch(@Param("studentId") Long studentId,
                        @Param("id") Long id,
                        @Param("newStatus") Integer newStatus,
                        @Param("expectStatus") Integer expectStatus);
```

调用点：`SeckillServiceImplV3MqAsync.java` 的 `cancel()` 里，`currentId` 已经在前面取到了，直接传进去。

#### 改造后的效果（预期）

| SQL | 改造前 `Com_update` 增量 | 改造后 |
|---|---|---|
| `updateStatusByOrderNo` | 4 | **1** |
| `updateStatusAndFailReason` | 4 | **1** |
| `updateStatusIfMatch` | 4 | **1** |

**这是本阶段唯一能"量化证明改造有效"的地方。必测。**

#### 如果不改会怎样

- 功能不会错（广播 UPDATE 结果仍然正确，只是慢）
- 但消费吞吐会明显下降，`capacity-scan-v4.ps1` 的 `排空` 列会变长
- **而且这是一个"看起来正常工作"的问题**——没有报错、没有异常、数据也正确，只有 `Com_update` 会告诉你真相

**这正是分表最危险的地方：错误是安静的。**

### 4.3 主键策略：现有 `IdType.AUTO` 与雪花算法的冲突

#### 现状

`src/main/java/com/example/seckill/entity/SeckillOrder.java:28`：

```java
@TableId(type = IdType.AUTO)
private Long id;
```

`IdType.AUTO` 的含义：**让数据库自增生成，MyBatis-Plus 通过 `getGeneratedKeys()` 读回**。

#### 分表后的三种可能

| 方案 | 配置 | 行为 | 风险 |
|---|---|---|---|
| **A. 保持 `AUTO` + 让 ShardingSphere 生成** | yaml 配 `keyGenerateStrategy: SNOWFLAKE`，实体保持 `IdType.AUTO` | ShardingSphere 拦下 INSERT，生成雪花 id，通过 `getGeneratedKeys()` 返回 | 需要实测验证（见下） |
| B. 改成 `IdType.ASSIGN_ID` | 实体改注解，yaml 不配 keyGenerate | MyBatis-Plus 自己生成雪花 id 并写进 INSERT | 最可控，但和 ShardingSphere 的雪花是两套算法 |
| C. 保持 `AUTO`，yaml 不配 keyGenerate | 什么都不做 | 每个分片的 `AUTO_INCREMENT` 各自从 1 开始 | **id 会重复**（`_0` 和 `_1` 都有 id=1） |

**C 一定错，不要选。**

#### A 方案的实测结果

配了 `keyGenerateStrategy` + `SNOWFLAKE` 之后：

```
[1] 不传 id 插入
    affected = 1
    读回 generatedKey = 1332010745902137344

[2] 同一学生再插一门课
    第二次 generatedKey = 1332010746304790529

[3] 显式给 id=777 插入
    插入成功（说明显式 id 优先于 keyGenerate）
```

查物理表：

```
student_id=5 -> order_1(id=1332010745902137344,no=SNOW-1)
               order_1(id=1332010746304790529,no=SNOW-2)
student_id=9 -> order_1(id=777,no=EXPLICIT)
```

**结论：A 方案可行。** `getGeneratedKeys()` 能拿到雪花 id，MyBatis-Plus 的 `IdType.AUTO` 链路不会断。

#### 推荐

**先用 A 方案，跑通再说。** 如果实测发现 `order.getId()` 返回 null（这才是真正的问题，因为消费者不用 id，但 `cancel` 用），再切 B。

**必须验证的一点**：插入后 `seckillOrder.getId()` 不为 null。这决定了退课走 `WHERE id = ?` 还是 `WHERE order_no = ?`。

（顺带说：改造 2 之后退课已经带了 `student_id`，其实可以只用 `order_no` 定位，`id` 不是必须的。但 `updateStatusIfMatch` 是靠 `id + status` 做的乐观更新，保留 `id` 更稳。）

---

## 5. 改造清单

### 5.1 新建文件（3 个）

| 文件 | 作用 | 难度 |
|---|---|---|
| `src/main/resources/sharding.yaml` | 分片配置（不要在 yaml 里写死密码） | 中 |
| `src/main/java/com/example/seckill/config/ShardingDataSourceConfig.java` | 把 `sharding.yaml` 的密码占位替换成环境变量后建 DataSource | 中 |
| `sql/V9__sharding_split.sql` | 把现有 `seckill_order` 拆成 4 张物理表 + 迁移数据 | 中 |

### 5.2 修改文件（5 个）

| 文件 | 改什么 | 行数 |
|---|---|---|
| `pom.xml` | 加 6 个 ShardingSphere 依赖 | +30 |
| `src/main/resources/application.yaml` | url 改成 `jdbc:shardingsphere:...`，注释掉原来的 datasource | ~10 |
| `src/main/java/com/example/seckill/mapper/SeckillOrderMapper.java` | 3 个方法加 `studentId` 参数 | 3 处 |
| `src/main/java/com/example/seckill/infra/mq/SeckillOrderConsumer.java` | 2 处调用传 `message.studentId()` | 2 处 |
| `src/main/java/com/example/seckill/service/impl/SeckillServiceImplV3MqAsync.java` | `cancel` 里的 `updateStatusIfMatch` 传 `currentId` | 1 处 |

### 5.3 一行都不要动的

- `SeckillServiceImplV0/V1Optimistic/V1Pessimistic/V1VersionCas/V2RedisLua` —— 这些版本不用 order 表，或者只读，分表不影响它们的逻辑（但 v0/v1 插入时 id 策略要一致）
- `CourseStockMapper` —— `course_stock` 是普通表，`!SINGLE` 登记即可
- `MqConsumeLogMapper` —— 同上
- `OrderController` / `OrderService` / `OrderServiceImpl` —— 查询逻辑不变（`WHERE student_id=?` 本来就带分片键）
- `RabbitConfig` / `SeckillMessageProducer` / `SeckillMessage` —— 消息结构不变
- `SecurityConfig` / `JwtUtil` —— 与分表无关

### 5.4 关于 `sharding.yaml` 里不能有明文密码

**`sharding.yaml` 会进 git**，所以**不能直接写数据库密码**。

方案：yaml 里写占位符，Java 代码读出来替换。

```yaml
# sharding.yaml —— 明文密码一律不写
dataSources:
  ds_0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: jdbc:mysql://localhost:3306/seckill?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8
    username: root
    password: __DB_PASSWORD__        # ← 占位符
    maximumPoolSize: 20
```

Java 侧读取 yaml，`String.replace("__DB_PASSWORD__", envPassword)`，写到临时文件，再用 `YamlShardingSphereDataSourceFactory.createDataSource(File)`。

**更简单的做法**：`YamlShardingSphereDataSourceFactory.createDataSource(byte[] yamlBytes)` 直接传替换后的字节数组，不落地临时文件。

```java
@Configuration
public class ShardingDataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(@Value("${DB_PASSWORD}") String dbPassword) throws Exception {
        String yaml = new String(
                getClass().getClassLoader().getResourceAsStream("sharding.yaml").readAllBytes(),
                StandardCharsets.UTF_8);
        String resolved = yaml.replace("__DB_PASSWORD__", dbPassword);
        return YamlShardingSphereDataSourceFactory.createDataSource(
                resolved.getBytes(StandardCharsets.UTF_8));
    }
}
```

**注意**：这时候 `application.yaml` 里的 `spring.datasource.*` 要注释掉，否则两个 DataSource 打架（Boot 会自动配一个 HikariDataSource，加上你的 ShardingSphereDataSource，MyBatis-Plus 不知道用哪个）。

**而且 `@Primary` 是必须的。**

---

## 6. 逐文件实现要点

### 6.1 `sharding.yaml` 完整内容

```yaml
# 分片配置：只分 seckill_order 一张表，其余用 !SINGLE 登记
# 密码用 __DB_PASSWORD__ 占位，由 ShardingDataSourceConfig 在运行时替换
dataSources:
  ds_0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: jdbc:mysql://localhost:3306/seckill?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8
    username: root
    password: __DB_PASSWORD__
    maximumPoolSize: 20
    minimumIdle: 5
    connectionTimeout: 3000
    poolName: ShardingHikari

rules:
  # ===== 分片表 =====
  - !SHARDING
    tables:
      seckill_order:
        actualDataNodes: ds_0.seckill_order_${0..3}
        tableStrategy:
          standard:
            shardingColumn: student_id
            shardingAlgorithmName: order_inline
        keyGenerateStrategy:
          column: id
          keyGeneratorName: snowflake
    shardingAlgorithms:
      order_inline:
        type: INLINE
        props:
          # 4 片：student_id % 4
          # 注意：扩到 8 片时要 % 8，所有数据要重分布 —— 见任务书 §10.4
          algorithm-expression: seckill_order_${student_id % 4}
    keyGenerators:
      snowflake:
        type: SNOWFLAKE

  # ===== 普通表：不登记就报 "Table or view 'xxx' does not exist" =====
  - !SINGLE
    tables:
      - ds_0.student
      - ds_0.course
      - ds_0.course_stock
      - ds_0.sys_user
      - ds_0.mq_consume_log
```

**`!SINGLE` 里 5 张表一行都不能漏。** 漏了哪张，哪张的接口就报错。

### 6.2 建表脚本 `sql/V9__sharding_split.sql`

```sql
SET NAMES utf8mb4;

-- 1. 先建 4 张物理表（结构必须与现有 seckill_order 完全一致）
USE seckill;

CREATE TABLE seckill_order_0 LIKE seckill_order;
CREATE TABLE seckill_order_1 LIKE seckill_order;
CREATE TABLE seckill_order_2 LIKE seckill_order;
CREATE TABLE seckill_order_3 LIKE seckill_order;

-- 2. 把现有数据按 student_id % 4 分发到 4 张表
INSERT INTO seckill_order_0 SELECT * FROM seckill_order WHERE student_id % 4 = 0;
INSERT INTO seckill_order_1 SELECT * FROM seckill_order WHERE student_id % 4 = 1;
INSERT INTO seckill_order_2 SELECT * FROM seckill_order WHERE student_id % 4 = 2;
INSERT INTO seckill_order_3 SELECT * FROM seckill_order WHERE student_id % 4 = 3;

-- 3. 校验：4 张表的行数之和必须等于原表行数
SELECT
    (SELECT COUNT(*) FROM seckill_order)   AS 原表,
    (SELECT COUNT(*) FROM seckill_order_0)
  + (SELECT COUNT(*) FROM seckill_order_1)
  + (SELECT COUNT(*) FROM seckill_order_2)
  + (SELECT COUNT(*) FROM seckill_order_3) AS 四表之和;

-- 4. ⚠️ 确认无误后，把原表改名留底（不要 DROP，留退路）
-- RENAME TABLE seckill_order TO seckill_order_bak_v4;
```

**顺序很重要**：先建表 + 迁数据 + 校验，**确认校验通过才改名原表**。否则一旦发现迁错，数据已经没法对比了。

### 6.3 `SeckillOrderMapper` 改造

```java
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /**
     * 条件改状态：只有当前状态等于 expectStatus 时才改。
     * studentId 是分片键，必须带上，否则这条 UPDATE 会广播到全部 4 个分片。
     */
    @Update("UPDATE seckill_order SET status = #{newStatus} " +
            "WHERE student_id = #{studentId} AND id = #{id} AND status = #{expectStatus}")
    int updateStatusIfMatch(@Param("studentId") Long studentId,
                            @Param("id") Long id,
                            @Param("newStatus") Integer newStatus,
                            @Param("expectStatus") Integer expectStatus);

    /**
     * 改状态：只有当前 status=0（待处理）时才改。
     * 必须带 studentId（分片键），否则广播 4 个分片。
     */
    @Update("UPDATE seckill_order SET status = #{newStatus} " +
            "WHERE student_id = #{studentId} AND order_no = #{orderNo} AND status = 0")
    int updateStatusByOrderNo(@Param("studentId") Long studentId,
                              @Param("orderNo") String orderNo,
                              @Param("newStatus") Integer newStatus);

    /**
     * 改成失败并记录原因。
     */
    @Update("UPDATE seckill_order SET status = #{newStatus}, fail_reason = #{failReason} " +
            "WHERE student_id = #{studentId} AND order_no = #{orderNo} AND status = 0")
    int updateStatusAndFailReason(@Param("studentId") Long studentId,
                                  @Param("orderNo") String orderNo,
                                  @Param("newStatus") Integer newStatus,
                                  @Param("failReason") String failReason);
}
```

**注意 `BaseMapper` 自带的方法不受影响**：
- `selectById` / `selectPage(new Page<>(...), wrapper.eq(SeckillOrder::getStudentId, id))` —— MyBatis-Plus 会生成 `WHERE student_id = ?`，**带分片键，只打一个分片** ✅
- `selectOne(... .eq(SeckillOrder::getOrderNo, orderNo))` —— 不带分片键，**广播 4 个分片** ⚠️ 这是 `OrderServiceImpl.query()` 里的那条，要么接受广播，要么也加 `student_id`

**建议**：`OrderServiceImpl.query(String orderNo)` 里已经有 `currentId`，直接改：

```java
SeckillOrder seckillOrder = orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
        .eq(SeckillOrder::getStudentId, currentId)   // 加这一行，带分片键
        .eq(SeckillOrder::getOrderNo, orderNo));
```

好处不只是性能——**带上 `student_id` 之后，越权查询（查别人的订单）自动变成"查不到"，比先查再比对 `studentId` 更安全**。

### 6.4 消费者改造（2 处）

`src/main/java/com/example/seckill/infra/mq/SeckillOrderConsumer.java`：

```java
// 原（第 67-68 行）
seckillOrderMapper.updateStatusAndFailReason(
        message.orderNo(), SeckillOrder.STATUS_FAILED, "库存不足");

// 改
seckillOrderMapper.updateStatusAndFailReason(
        message.studentId(), message.orderNo(), SeckillOrder.STATUS_FAILED, "库存不足");
```

```java
// 原（第 76-77 行）
int rows = seckillOrderMapper.updateStatusByOrderNo(
        message.orderNo(), SeckillOrder.STATUS_SUCCESS);

// 改
int rows = seckillOrderMapper.updateStatusByOrderNo(
        message.studentId(), message.orderNo(), SeckillOrder.STATUS_SUCCESS);
```

### 6.5 退课改造（1 处）

`src/main/java/com/example/seckill/service/impl/SeckillServiceImplV3MqAsync.java` 的 `cancel()`：

```java
// 原
int affected = seckillOrderMapper.updateStatusIfMatch(
        order.getId(), SeckillOrder.STATUS_CANCELLED, SeckillOrder.STATUS_SUCCESS);

// 改
int affected = seckillOrderMapper.updateStatusIfMatch(
        currentId, order.getId(), SeckillOrder.STATUS_CANCELLED, SeckillOrder.STATUS_SUCCESS);
```

（`currentId` 在 `cancel()` 开头已经取到，直接传。）

---

## 7. 配置变更

### 7.1 `application.yaml`

```yaml
spring:
  datasource:
    # 分表后由 ShardingDataSourceConfig 提供 DataSource，这里必须注释掉
    # url: jdbc:mysql://localhost:3306/seckill?...
    # username: root
    # password: ${DB_PASSWORD:}
    # driver-class-name: com.mysql.cj.jdbc.Driver
    # hikari:
    #   maximum-pool-size: 50
    #   ...

  # 让 Boot 不要自动配 DataSource（否则和 ShardingSphere 的打架）
  autoconfigure:
    exclude:
      - org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
      - org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
```

**`DataSourceAutoConfiguration` 必须排除**，否则你的 `@Primary` DataSource 和 Boot 自动配的 HikariDataSource 会冲突。

### 7.2 启动参数

```powershell
# sharding 版本不需要 --seckill.strategy 切换
# 但要保证 DB_PASSWORD 环境变量存在（ShardingDataSourceConfig 用它）
$env:DB_PASSWORD = "<你的密码>"
```

---

## 8. 数据迁移

### 8.1 迁移步骤

```powershell
# 1. 备份
mysqldump -u root -p seckill seckill_order > backup_before_sharding.sql

# 2. 跑建表 + 迁移脚本
mysql -u root -p --default-character-set=utf8mb4 < sql/V9__sharding_split.sql

# 3. 校验（脚本里有 SELECT，人工确认两数相等）
#    原表行数 == 四表之和

# 4. 改名原表留底
# RENAME TABLE seckill_order TO seckill_order_bak_v4;

# 5. 启动应用，跑自测
```

### 8.2 回滚

```sql
-- 如果分表后有问题，改回来
RENAME TABLE seckill_order_bak_v4 TO seckill_order;
DROP TABLE seckill_order_0, seckill_order_1, seckill_order_2, seckill_order_3;

-- 代码回滚：git checkout 到 tag v3-mq-async
```

**留底表 `seckill_order_bak_v4` 不要急着删**，跑完全部验收再说。

### 8.3 ⚠️ 迁移的隐蔽问题

原表有 `UNIQUE KEY uk_order_no`，迁到 4 张表后每张表也有这个唯一索引，但**只在自己表内有效**。

如果原表里已经有重复 `order_no`（理论上不该有，因为原表有唯一索引），迁移不会报错，**但迁完之后就没有约束了**。

迁移后建议跑一次：

```sql
-- 检查 4 张表之间是否有重复 order_no
SELECT order_no, COUNT(*) FROM (
    SELECT order_no FROM seckill_order_0
    UNION ALL SELECT order_no FROM seckill_order_1
    UNION ALL SELECT order_no FROM seckill_order_2
    UNION ALL SELECT order_no FROM seckill_order_3
) t GROUP BY order_no HAVING COUNT(*) > 1;
```

**结果必须为空。**

---

## 9. 验证方案

### 9.1 能测的部分

| # | 测什么 | 怎么测 | 期望 |
|---|---|---|---|
| 1 | 分片路由正确 | 插入 `student_id=1..8`，查 4 张物理表 | `_1/_2/_3/_0/_1/_2/_3/_0` |
| 2 | 普通表可读写 | `POST /api/admin/cache/stock/88`（走 course_stock） | 200 |
| 3 | 雪花 id 生效 | 插入后查物理表 id | 19 位 Long，不是 1/2/3 |
| 4 | `getGeneratedKeys` 可用 | 插入后 `order.getId()` | 不为 null |
| 5 | 跨表事务可回滚 | 制造消费失败，看库存 | 回滚，不变 |
| 6 | 热 UPDATE 只打 1 个分片 | `Com_update` 差值 | **+1** |
| 7 | `uk_order_no` 跨分片失效 | 两个学生插同一 order_no | 都成功，分别在两张表 |
| 8 | `uk_student_course` 仍然有效 | 同一学生插同一课程两次 | 第二次报 `Duplicate entry` |
| 9 | 全链路正确 | 抢课→落单→退课→重选 | 与 v3 一致 |

### 9.2 关键测量：`Com_update` 对比

**这是本阶段的核心数据。方法：**

```sql
-- 测量前
SHOW GLOBAL STATUS LIKE 'Com_update';

-- 跑一轮压测（100 单）

-- 测量后
SHOW GLOBAL STATUS LIKE 'Com_update';
-- 差值 = 实际执行了多少次 UPDATE
```

**三个数据点都要测：**

| 场景 | 期望 `Com_update` 增量 |
|---|---|
| 不分表（v3 基线） | ~200（100 单 × 2 条 SQL） |
| 分表但没改 SQL（广播） | ~500（100 × (4+1)） |
| 分表且改了 SQL（带分片键） | ~200 |

**第二个和第三个的对比，就是 §4.2 改造的价值证明。**

### 9.3 ⚠️ 测不出来的部分（必须写进报告）

**这一节不能空着。写清楚"测不出"，比伪造一个数字强一百倍。**

| # | 测不出什么 | 为什么 |
|---|---|---|
| 1 | 深分页的性能退化 | 2000 行数据下 offset 1500 只要 6ms，没有退化可测 |
| 2 | 跨分片聚合的延迟 | 4 个分片各几百行，`COUNT` 只要 39ms |
| 3 | 分片带来的吞吐提升 | 数据量太小，分不分表都是 1600 RPS |
| 4 | 扩容（4→8 片）的迁移成本 | 需要千万级数据才能体现 |
| 5 | 真实生产的分片倾斜 | 100 个学生均匀分布，没有热点学生 |
| 6 | ShardingSphere 自身的 CPU 开销 | 需要 Prometheus 级别的采样，本机内存不够 |

**预期报告里这句话会反复出现：** "该指标在本地数据规模下无意义，原因是 X。真实环境需要 Y 量级的数据才能测出。"

### 9.4 自测清单（14 项）

| # | 场景 | 期望 |
|---|---|---|
| 1 | 应用启动 | 无 `SPI-00001` / `Invalid tag` / `Table does not exist` |
| 2 | 启动日志里能看到 5 张 SINGLE 表 | 无警告 |
| 3 | 学生登录 | 200 |
| 4 | `GET /api/course/88` | 200，能读到 `course_stock` |
| 5 | 抢课 | 200，返回 `status: 0` |
| 6 | 1 秒后查订单 | `status: 1` |
| 7 | 查物理表 | 订单落到了正确的 `_N` 表 |
| 8 | 查 id | 19 位雪花，不是自增 1/2/3 |
| 9 | 重复抢课 | 409「你已选过该课程」 |
| 10 | 退课 | 200，`status: 3` |
| 11 | 退课后重选 | 200，复用同一 `orderNo` |
| 12 | `GET /api/order/my` | 只返回自己的订单，分页正确 |
| 13 | 两个学生同一 order_no | 都成功（证明唯一索引失效，这是**预期行为**） |
| 14 | 消费失败（造坏消息） | 进死信，事务回滚，库存不变 |

---

## 10. 压测方案

### 10.1 复用 v4 的脚本

`loadtest/capacity-scan-v4.ps1` 可以直接用，但要注意：

- **必须改 `reset` 逻辑**：现在只 `UPDATE course_stock` 和 `TRUNCATE seckill_order`。分表后要清 4 张表：
  ```sql
  TRUNCATE seckill_order_0;
  TRUNCATE seckill_order_1;
  TRUNCATE seckill_order_2;
  TRUNCATE seckill_order_3;
  ```
  **注意 `TRUNCATE seckill_order` 会失败**（那是逻辑表，物理上不存在）。

- 计数器 `Com_update` 要单独采：在 `Reset-Data()` 前后各读一次 `SHOW GLOBAL STATUS`。

### 10.2 压测对比表设计

| 版本 | P95 | 成功单 | 超卖 | 排空 | `Com_update`/100单 |
|---|---|---|---|---|---|
| v3（不分表） | 66ms | 100 | 0 | ? | ~200 |
| v5（分表，未改 SQL） | ? | 100 | 0 | ? | ~500 |
| v5（分表，已改 SQL） | ? | 100 | 0 | ? | ~200 |

**第三行的意义**：证明"改造后代价被消掉了"。

### 10.3 ⚠️ 别指望 P95 变好

v3 是 66ms，v5 分表后**大概率还是 60~70ms**。因为：

- HTTP 路径上没有新增 SQL
- 消费者在后台跑，不阻塞响应
- 唯一变化是消费者慢了，但 100 单的消费在 1 秒内完成，压测 15 秒内不影响

**如果实测 P95 变差很多（比如 150ms+），说明有问题**（可能是 ShardingSphere 的 SQL 解析开销、或者 DataSource 配置不对）。

### 10.4 扩容为什么这么痛（面试必问）

现在是 `student_id % 4`。如果数据涨到 4 张表装不下，要扩到 8 张：

```
student_id=1  → %4=1 → _1    → %8=1 → _1   ✅ 不变
student_id=2  → %4=2 → _2    → %8=2 → _2   ✅ 不变
student_id=3  → %4=3 → _3    → %8=3 → _3   ✅ 不变
student_id=4  → %4=0 → _0    → %8=4 → _4   ❌ 要迁！
student_id=5  → %4=1 → _1    → %8=5 → _5   ❌ 要迁！
```

**一半的数据要迁移。** 如果从 4 扩到 16，`%4=0` 的数据要拆到 `_0/_4/_8/_12`，迁移量仍是 75%。

**解决办法（都是真实生产用的）**：
1. **预分区**：一开始就建 1024 张表（比如 `_0000` ~ `_1023`），映射 `student_id % 1024`，只在物理库级别扩。`_0000~_0255` 放库 1，`_0256~_0511` 放库 2……扩库时只迁移整段，不重算哈希。
2. **一致性哈希**：节点变化只影响相邻区间，但实现复杂，且分片不均匀。
3. **基因法**：把分片基因编进 id，从 id 就能算回分片（`id % 1024`）。

**这段是面试的加分项，比"我用了 ShardingSphere"有含量得多。**

---

## 11. 预期 vs 实测（先填预期，跑完填实测）

| 指标 | 预期 | 实测 | 偏差原因 |
|---|---|---|---|
| 分片路由 | 完全正确 | | |
| 启动时间 | +2 秒（ShardingSphere 初始化） | | 实测 v4 是 11 秒，预期变 13 秒 |
| 单次插入耗时 | +1~3ms（SQL 改写） | | |
| `Com_update`/100单（未改 SQL） | ~500 | | |
| `Com_update`/100单（已改 SQL） | ~200 | | |
| HTTP P95 | 60~80ms | | |
| 排空时间 | 变长 1.5~2 倍（未改 SQL）/ 基本不变（已改） | | |
| 深分页退化 | **测不出** | | 数据量不足 |
| 分片吞吐提升 | **测不出** | | 数据量不足 |

**先写预期再填实测，偏差比数字本身更值钱。** 如果你预期 +2 秒启动、实测 +8 秒，那 6 秒的意外就是新知识。

---

## 12. 排查顺序（出问题时按这个顺序）

| # | 症状 | 第一步查什么 | 命令 |
|---|---|---|---|
| 1 | 启动报 `Invalid tag: !SHARDING` | pom 有没有 `shardingsphere-sharding-core` | `mvn dependency:tree \| Select-String sharding` |
| 2 | 启动报 `SPI-00001 ... ContextManagerBuilder` | pom 有没有 `standalone-mode-core` | 同上 |
| 3 | 启动报 `SPI-00001 ... PrivilegeProvider` | pom 有没有 `authority-simple` | 同上 |
| 4 | 启动报 `NullPointerException ... StorageUnit` | pom 有没有 `infra-data-source-pool-hikari` | 同上 |
| 5 | 运行时 `Table 'xxx' does not exist` | 这张表有没有写进 `!SINGLE` | 看 yaml |
| 6 | 两个 DataSource 冲突 | `application.yaml` 有没有排除 `DataSourceAutoConfiguration` | 看 yaml |
| 7 | 数据落到错误的分片 | `algorithm-expression` 有没有写错 | 用 `student_id=4` 试，应落 `_0` |
| 8 | id 是 1/2/3 而不是雪花 | `keyGenerateStrategy` 有没有配 | 看 yaml |
| 9 | `order.getId()` 是 null | 是不是走了 `IdType.ASSIGN_ID` 以外的路 | 见 §4.3 |
| 10 | UPDATE 很慢、队列排空变长 | 是不是漏了 `student_id` | `Com_update` 差值 |

**最快的一步永远是**：`SHOW GLOBAL STATUS LIKE 'Com_update'` 测一次差值。如果一次请求让它增加 4 以上，就是广播了。

---

## 13. 必踩的坑

1. 🔴 **5.5.x 的模块拆分**：`shardingsphere-jdbc:5.5.3` 把 6 个必需模块标成 test scope，不加就崩。**5.4 及之前的教程全部无效。**

2. 🔴 **`!SINGLE` 漏表**：漏哪张，哪张的接口报 `Table or view 'xxx' does not exist`。**5 张表全要写。**

3. 🔴 **无分片键的 UPDATE 广播**：`WHERE order_no=?` 每次打 4 个分片。**这是最隐蔽的性能坑——不报错、数据也对、就是慢。**

4. 🔴 **`uk_order_no` 失效**：全局唯一约束在分表后不存在。靠雪花 ID 保证，不要靠数据库。

5. ⚠️ **"表不存在"不一定是"没建表"**：先查 yaml 有没有登记，再查数据库有没有这张表。

6. ⚠️ **`TRUNCATE seckill_order` 会失败**：逻辑表不能 TRUNCATE，要清 4 张物理表。

7. ⚠️ **`sharding.yaml` 不能写明文密码**：会被 git 收进去。用占位符 + 运行时替换。

8. ⚠️ **两个 DataSource 打架**：必须排除 `DataSourceAutoConfiguration` + `@Primary`。

9. ⚠️ **`student_id % 4` 里的空格**：`student_id%4` 和 `student_id % 4` 都能跑，但写成 `student_id %  4`（两个空格）某些版本会解析失败。保守写法：`seckill_order_${student_id % 4}`。

10. ⚠️ **yaml 里的 `${}` 和 Spring 的 `${}` 冲突**：`sharding.yaml` 是被 ShardingSphere 读的，不是 Spring。如果两个都用 `${}`，Spring 会尝试解析并报 `Could not resolve placeholder`。**所以 `sharding.yaml` 要用 `createDataSource(byte[])` 手动加载，不要让它进 Spring 的配置体系。**

11. ⚠️ **`@TableId(type = IdType.AUTO)` 在分表后语义变了**：现在靠 ShardingSphere 的 `getGeneratedKeys()`，不是数据库自增。必须在 `SeckillOrder` 上加注释说明。

12. ⚠️ **分表不改 `mq_consume_log`**：它不在分片规则里，用 `!SINGLE` 登记即可。有人会想"顺便分一下"——没有意义，10000 行的表分 4 片只会让查询变复杂。

13. ⚠️ **本地测不出性能收益**：不要为了报告好看去伪造数字。见 §9.3。

---

## 14. 明确不做

| # | 不做什么 | 为什么 |
|---|---|---|
| 1 | 不装 ShardingSphere-Proxy | 又要一个 JVM，内存不够 |
| 2 | 不分库（只分表） | 本地只有 1 个 MySQL 实例；分库要 4 个实例，内存翻 4 倍 |
| 3 | 不分 `course_stock` | 100 行，分它没有意义 |
| 4 | 不做读写分离 | 那是另一版（可以留给 v6 或真实项目） |
| 5 | 不用分布式主键之外的方案（比如 Redis 取号器） | 雪花够用，且不依赖外部服务 |
| 6 | 不做数据同步/双写 | 单机迁移，停服迁移即可 |
| 7 | 不调 ShardingSphere 的线程池参数 | 本地测不出差别，调了也没依据 |
| 8 | 不改 `BaseMapper` 的查询行为 | MyBatis-Plus 的 `selectPage` 带 `student_id` 时本来就只打一个分片 |
| 9 | 不做动态分片（扩容） | 需要 3 个以上数据量级才能验证，写进报告作为"未验证" |
| 10 | 不重构 v0/v1/v2 的实现 | 它们不用 order 表，或者只用 `SELECT` |

---

## 15. 面试话术

**Q1：为什么选 ShardingSphere-JDBC 不选 Proxy？**
> 主要原因是部署成本。Proxy 要额外跑一个 JVM 进程，我的机器上 IDEA 一个就吃 3.1 GB，再开 Proxy 压测数据会被内存换页污染。JDBC 驱动是 jar 依赖，和应用同进程，我能保证测出来的数是业务代码的耗时，不是两个进程抢内存的耗时。代价是应用要改 DataSource 配置，Proxy 则是零侵入。

**Q2：为什么用 `student_id` 做分片键？**
> 我统计了流量构成：最高频的是"查我的订单"（`WHERE student_id = ?`）和"查重"（`WHERE student_id=? AND course_id=?`），都带 `student_id`。用 `course_id` 做分片键会让"查我的订单"退化成全分片扫描，而且热门课程还是单点——分片救不了热门课程，那要靠缓存和队列。
>
> 但 `student_id` 有个问题：消费者改订单状态时只有 `order_no`，没有 `student_id`，这条 UPDATE 会广播到全部 4 个分片。我用 `Com_update` 计数器测到了：一次这样的 UPDATE 会让它增加 4。所以我改造了 Mapper，让消息里带的 `studentId` 也传进去，把增量压回 1。

**Q3：分表后全局唯一索引怎么办？**
> 表级唯一索引在分表后各管各的。我实测过：同一个 `order_no` 可以同时存在于两张物理表里。所以全局唯一性不能靠数据库，得靠雪花 ID——19 位 Long，时间戳+机器号+序列号，本地生成不依赖数据库，同一毫秒同一个 JVM 里序列号递增，冲突概率极低。
>
> 但要注意，`uk_student_course`（同一个学生不能选同一门课两次）反而是有效的，因为分片键就是 `student_id`，同一个学生必然落在同一张表。这是运气好，不是设计出来的——如果分片键换成 `course_id`，这条约束也会失效。

**Q4：分表带来了多少额外开销？**
> 我用 MySQL 的 `Com_update` 计数器量化了，这是数据库自己记的账：不分表时 100 单需要约 200 次 UPDATE；分表后如果 SQL 不改，因为 `WHERE order_no=?` 要广播 4 个分片，变成约 500 次；改造 Mapper 加上分片键之后回到约 200 次。所以分表本身不慢，慢的是"没想清楚哪条 SQL 带了分片键"。

**Q5：分表后性能提升了多少？**
> 在我的数据规模下**没有提升**，因为只有 1 万行订单。我实测了跨分片深分页：offset 从 0 到 1500，耗时全部 6~7ms，没有退化可测。分表解决的是"数据量大到单表撑不住"，不是"并发高"——后者我已经用 Redis 门禁 + MQ 异步解决了。所以这一版我验证的是机制和代价，不是性能收益。真实环境需要 500 万到 2000 万行才能测出差别。

**Q6：扩容怎么办？**
> 现在是 `student_id % 4`，扩到 8 片时 `% 4 = 0` 的那批学生（占 1/4）会散到 `_0` 和 `_4`，要迁移。实际生产通常用预分区：一开始建 1024 张逻辑表，映射成 `student_id % 1024`，物理上放在少量库上，扩库时只迁整段，不重算哈希。或者用基因法把分片基因编进 id。

---

## 附录 A：与《项目开发文档》§8 的差异

| 文档写的 | 实测 | 以哪个为准 |
|---|---|---|
| "ShardingSphere 5.x 配置示例" | 5.5.3 需要额外 5 个依赖 | **任务书**（文档写的时候 5.5 还没拆模块） |
| "按 order_no 查询慢 8ms→25ms" | 2000 行下测不出差异 | **任务书**，且标注"数据量不足" |
| "course_id 深分页 15ms→60ms" | offset 1500 仍 6ms | **任务书**，同 |
| "唯一索引跨分片失效" | ✅ 实测确认（`SAME-NO` 在两张表） | 一致 |
| "分片键选 student_id，`% 4`" | ✅ 实测确认 | 一致 |
| "非分片键全分片扫描" | ✅ 实测确认，且量化了 `Com_update` 增量 4 | **任务书更新更具体** |
| "改分片数要迁移" | ✅ 推导正确 | 一致 |
| 未提及 | **`!SINGLE` 登记普通表** | **任务书新增（文档漏了这条，会导致所有非分片表不可用）** |
| 未提及 | **`uk_student_course` 因分片键相同而仍然有效** | **任务书新增** |

---

## 附录 B：本文件的实测数据来源

| 数据 | 怎么来的 | 日期 |
|---|---|---|
| 5 个模块的报错原文 | 逐个删依赖跑一遍，记录异常 | 2026-11-24 |
| 分片路由 `1..8 → _1/_2/_3/_0...` | 临时库插入 8 行后直连物理表查 | 2026-11-24 |
| `uk_order_no` 跨分片失效 | 插 `SAME-NO` 到两个学生，查 4 张表各 1 行 | 2026-11-24 |
| `Com_update` 增量 4 / 1 | `SHOW GLOBAL STATUS LIKE 'Com_update'` 前后差值 | 2026-11-24 |
| 深分页 6~7ms | offset 0/100/500/1000/1500 各跑一次 | 2026-11-24 |
| `!SINGLE` 能让普通表读写 | 加规则后测 SELECT / UPDATE / INSERT / 跨表事务回滚 | 2026-11-24 |
| 雪花 id + `getGeneratedKeys` | 不传 id 插入，读回 `1332010745902137344` | 2026-11-24 |
| 启动时间 +1.7~1.9 秒 | `YamlShardingSphereDataSourceFactory.createDataSource` 计时 | 2026-11-24 |

**所有探针用完后已清理**：临时库 `seckill_probe` / `seckill_probe2` / `seckill_probe3` / `seckill_probe5` 全部 DROP，`$env:TEMP\dsh-ss*` 目录全部删除。你的 `seckill` 库实测完好（100 课 / 10000 学生 / 1 sys_user / 容量 100 / 订单 0）。

---

## 最后一句

**这一版最大的价值不是你写出了一个能跑的 4 分片方案，而是你手里有了一组"分表到底让你多付出什么"的数字。**

当面试官问"分表有什么代价"，别人答"会有一致性问题"，你能答"我的消费者每处理一条消息，UPDATE 从 2 次变成 5 次，因为 `WHERE order_no=?` 不带分片键，ShardingSphere 会广播到 4 个分片，我用 `Com_update` 计数器测出来的"。**这是做过和看过的区别。**
