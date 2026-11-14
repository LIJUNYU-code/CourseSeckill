# 阶段 5 任务书：v3-mq-async（RabbitMQ 异步削峰）

> 状态：**待你实现**
> 前置：`v2-redis-lua` 已完成并打 tag（提交 `539d703`）
> 本文所有环境数据均为本机实测，不是查文档抄来的

---

## 目录

- [§0 这一版要解决什么](#0-这一版要解决什么)
- [§1 前置事实（全部实测）](#1-前置事实全部实测)
- [§2 最重要的认知转变：返回 200 ≠ 已选上](#2-最重要的认知转变返回-200--已选上)
- [§3 目标架构](#3-目标架构)
- [§4 消息可靠性三问（本阶段核心）](#4-消息可靠性三问本阶段核心)
- [§5 幂等设计](#5-幂等设计)
- [§6 死信队列](#6-死信队列)
- [§7 接口契约变更](#7-接口契约变更)
- [§8 逐文件实现要点](#8-逐文件实现要点)
- [§9 配置变更](#9-配置变更)
- [§10 单元测试：能测什么，测不了什么](#10-单元测试能测什么测不了什么)
- [§11 压测方案（数成功单的方法变了）](#11-压测方案数成功单的方法变了)
- [§12 预期 vs 实测](#12-预期-vs-实测)
- [§13 自测清单（14 项）](#13-自测清单14-项)
- [§14 验收标准](#14-验收标准)
- [§15 排查顺序](#15-排查顺序)
- [§16 必踩的坑（13 条）](#16-必踩的坑13-条)
- [§17 明确不做](#17-明确不做)
- [§18 面试话术](#18-面试话术)
- [附录 A：与《项目开发文档》的差异](#附录-a与项目开发文档的差异)

---

## §0 这一版要解决什么

v2 压测报告 §5 得出的结论是这一版的靶子，原话：

> 门禁挡住 200 个请求后，剩下 100 个成功请求仍串行在**同一行** MySQL 行锁上，
> 100 次 × 约 2ms ≈ 200ms 就是新的下限。

也就是说，v2 的 P95 = 255.6ms 里，**绝大部分时间不是花在"算"上，而是花在"排队"上**——
100 个请求排在 `UPDATE course_stock SET stock = stock - 1 WHERE course_id = 88` 这一条 SQL 后面。

v3 要做的事只有一句话：

> **把这段排队从 HTTP 请求路径上挪走。**

用户点完按钮，服务器立刻回一句"收到了，正在处理"，
真正去抢那行库存的是后台消费者。

### 0.1 但这不是免费的

必须先把代价说清楚，否则后面每个决定都看不懂：

| 代价 | 具体表现 |
|---|---|
| **语义变了** | 返回 200 只代表"请求进队列了"，不代表"你选上了" |
| **要能扛住消息丢** | 网络、broker 重启、消费者崩溃，任一环节都可能丢消息 |
| **要能扛住消息重复** | 至少一次投递，同一条消息可能被消费 2 次以上 |
| **要能扛住消费失败** | 消费者抛异常时，消息去哪？重试几次？重试完还失败呢？ |
| **要能观察** | 队列堆积多少、成功多少、失败多少，不能靠猜 |

这四件事就是 §4 的全部内容。**v3 的难度不在这段代码，在这四件事**。

### 0.2 最大的坑：v3 可能比 v2 还慢

如果只是把 `courseStockMapper.tryDecrement()` 从 HTTP 线程搬到消费者线程，
HTTP 侧确实快了，但如果你在 HTTP 线程里**等结果**（比如轮询到订单成功才返回），
那就等于什么也没优化，还多了两次网络往返。

**v3 的快，来自"不等"。** 一旦你写了 `while (status != SUCCESS) sleep()`，
这一版就白做了。

---

## §1 前置事实（全部实测）

### 1.1 RabbitMQ 容器

```
容器名     seckill-rabbitmq
镜像       rabbitmq:3-management
版本       RabbitMQ 3.13.7 / Erlang 26.2.5.16
内存上限   536870912 字节 = 512 MB
CPU 上限   0.5 核  ← 注意这个
重启策略   unless-stopped
端口       5672（AMQP）、15672（管理台，账号 seckill / 密码见本地配置）
```

**容器不会自动启动**——Docker 引擎本身要先起来：

```powershell
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe" -WindowStyle Hidden
# 实测约 12 秒引擎就绪（`docker version --format {{.Server.Version}}` 能回版本号）
```

容器如果停在 `Exited`，`docker start seckill-rabbitmq`，管理台约 3~30 秒可用。

### 1.2 实测吞吐上界（这是我为了这份任务书专门测的）

用 amqp-client 5.30.0 直连，消息体 114 字节（和 `SeckillMessage` 的 JSON 差不多大）：

| 操作 | 消息数 | 吞吐 | 说明 |
|---|---|---|---|
| `basicPublish` | 5,000 × 3 轮 | **52,083 / 53,763 / 54,945 msg/s** | 无 confirm，纯发 |
| `basicPublish` | 20,000 × 2 轮 | **64,516 / 63,291 msg/s** | 批量更大时更高 |
| `basicConsume` autoAck | 10,000 | **47,619 msg/s** | 不 ack |
| `basicConsume` manualAck + QoS 100 | 10,000 | **21,598 msg/s** | 手动 ack |
| 逐条 `basicGet` 拉取 | 5,000 × 3 轮 | 3,104 ~ 3,554 msg/s | 别用这个数，它测的是网络往返 |

结论：**手动 ack 大约是自动 ack 的一半**——因为每条消息多了一次 ack 往返。
21,598 msg/s 对本项目（100~300 条消息）完全够用，**瓶颈不在这里**。

### 1.3 堆积多少条会出问题（重要，且我一开始测错了）

v3 的全部假设是"消息能堆着"。所以要把"能堆多少"测准。

**⚠️ 先说一次测错的经历，因为它很可能也是你的第一反应。**
我最初的探针是"灌满 → `queuePurge()` → 读内存"，得到"20 万条只涨 4 MB、300 万条才 197 MB"。
这个数据是假的：`purge` 之后内存已经还回去了，我读到的是**清空后的残留值**，
不是堆满时的值。测这种指标必须在**清空之前**读。

改用一个"塞满后不清空、挂住 45 秒、再从外部读 `docker stats`"的探针，才得到可信数字：

```
基线（队列空）      MEM=141.1MiB / 512MiB    rabbitmqctl Total memory=0.1621 gb
塞 500000 条并挂住  MEM=234.5MiB / 512MiB    rabbitmqctl Total memory=0.2471 gb
                    queue depth=500000       CPU=0.21%
```

→ **50 万条持久化消息 ≈ 93.4 MiB ≈ 每条 196 字节**（含 AMQP 帧头、队列索引、per-queue 进程堆开销）。

继续加大到 185 万条（同一个探针，每 50 万抽样）：

| 已发消息数 | 内存 | 备注 |
|---|---|---|
| 600,000 | 230.2 MiB | |
| 1,200,000 | 260.9 MiB | |
| 1,850,000 | 255.0 MiB | 反而回落 |

发布速率稳定在 **30,212 ~ 30,495 msg/s**（0.5 核的上界），
全程 `BlockedListener` 一次没触发，`OOMKilled=false`，`RestartCount=0`。

**关键结论：内存随消息数增长是次线性的，并且在 230~260 MiB 附近就饱和了。**
原因在内存构成里能看出来——`binary` 只占 **0.12%**。
持久化消息本体落在 `/var/lib/rabbitmq/mnesia/.../msg_stores/*/0.rdq` 这些**磁盘**文件上
（`queue_index_embed_msgs_below = 4096`，大于这个尺寸的消息不内嵌进索引），
内存里主要留的是索引和队列进程状态，所以 185 万条也没把容器顶爆。

### 1.3.1 我一开始写的"205 MB 水位"是错的

任务书初稿说"水位 0.4 × 512 MB ≈ 205 MB"，这个算法漏了一个前提。实测：

| 项 | 实测值 |
|---|---|
| cgroup 内存上限 | `536870912`（512 MB） |
| RabbitMQ 认为的机器总内存 | `4107956224`（**3.83 GB**） |
| `vm_memory_high_watermark` | `0.4` |
| → 由此算出的水位 | 3.83 GB × 0.4 ≈ **1.53 GB** |

容器里 `MemTotal` 读到的是 **WSL 虚拟机**的大小，不是 cgroup 限制。
所以 RabbitMQ 算出的 1.53 GB 水位**高于**容器真正的 512 MB 上限——
水位这个保护**在这个容器里永远不会先触发**。

这带来两个必须知道的后果：

1. **不要指望 `basicPublish` 会"卡住"来提醒你内存到顶了**（§16 第 9 条已按此改正）。
   本容器更可能的失败方式是**被内核 OOM killer 直接杀掉**，表现为 `docker ps` 里容器消失、
   `OOMKilled=true`。查法：`docker inspect seckill-rabbitmq --format '{{.State.OOMKilled}} {{.RestartCount}}'`。
2. 好消息是**按本项目的消息量根本到不了这一步**。100~300 条消息、每条 114 字节，
   内存增量在一两 MB 量级，属于噪声。真正的风险是"改了消息体，把整个课程对象塞进去"，
   那也从"每条 196 字节"变成"每条几 KB"，但要堆到 512 MB 仍需几十万条。

**所以 §17「明确不做」里那条"不调 RabbitMQ 内存 CPU 参数"是站得住的**：
不是因为我们留了余量，而是因为这个量级根本碰不到边界。

### 1.4 依赖版本（已解析，无需改 pom）

```
spring-boot-starter-amqp 4.1.1
  └─ spring-boot-amqp 4.1.1
      └─ spring-rabbit 4.1.1
          ├─ spring-amqp 4.1.1
          └─ com.rabbitmq:amqp-client 5.30.0
```

`spring-boot-starter-amqp` **已经在 pom 里了**（阶段 1 就加好了），本阶段不用动 pom。

⚠️ amqp-client 5.30.0 **需要 Netty**（`io.netty.channel.Channel`）。
在 Spring Boot 里它是通过 starter 传进来的，不用管；
但如果你写探针程序，只放 `amqp-client.jar` 会报 `NoClassDefFoundError: io/netty/channel/Channel`。

### 1.5 Jackson 3 的坑（会咬人）

本项目用的是 **Jackson 3**（`tools.jackson.*`），而 RabbitMQ 的消息转换器有两个类：

| 类 | 底层 Jackson | 本项目能用吗 |
|---|---|---|
| `Jackson2JsonMessageConverter` | `com.fasterxml.jackson.databind`（Jackson 2） | ❌ 能编译，但没有对应 Bean |
| `JacksonJsonMessageConverter` | `tools.jackson.databind`（Jackson 3） | ✅ **用这个** |

实测确认：`spring-amqp-4.1.1.jar` 里 `JacksonJsonMessageConverter` 的字节码引用的是 `tools/jackson`。
**这和你阶段 1 在 `SecurityConfig` 里踩的是同一个坑**（`ObjectMapper` 有两个，只有一个有 Bean）。

### 1.6 数据库现状

```
mq_consume_log   id bigint PRI auto_increment
                 msg_id varchar(64) NOT NULL UNI
                 status tinyint NOT NULL DEFAULT 0
                 error_msg varchar(500) NULL
                 create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
                 （没有 update_time）

seckill_order    结构不变，status tinyint NOT NULL DEFAULT 0
                 0 待处理 / 1 成功 / 2 失败 / 3 已退课
```

**`mq_consume_log` 存在的唯一理由就是幂等**（§5），不是为了记日志。
它的 `uk_msg_id` 是整个幂等方案的物理保证。

### 1.7 现有代码：一行 MQ 代码都没有

```
controller/  AuthController / CacheAdminController / CourseController
             OrderController / SeckillController
infra/cache/ StockCacheService / StockLuaScript / impl/StockCacheServiceImpl
mapper/      Course / CourseStock / SeckillOrder / Student / SysUser Mapper
service/impl/ SeckillServiceImpl（v0）/ V1Optimistic / V1Pessimistic
              / V1VersionCas / V2RedisLua
```

搜 `RabbitTemplate|@RabbitListener|AmqpTemplate|RabbitAdmin` → **零命中**。
全部由你新建。

### 1.8 好消息：`GET /api/order/{orderNo}` 不用改

`OrderServiceImpl.getStatusText()` 已经是：

```java
return switch (status) {
    case 1 -> "已成功";
    case 2 -> "已失败";
    case 3 -> "已退课";
    default -> "待处理";     // ← status=0 走这里，早就写好了
};
```

所以 v3 **不需要改** `OrderController`、`OrderService`、`OrderServiceImpl`、`OrderResponse`。
客户端轮询现成的接口就能看到"待处理 → 已成功"。

---

## §2 最重要的认知转变：返回 200 ≠ 已选上

v0 到 v2 都是**同步**的：

```
HTTP 请求进入 → 查课程 → 扣库存 → 写订单 → 返回 SUCCESS
        └──────────── 这一整段都在同一个线程里 ────────────┘
```

v3 是**异步**的：

```
HTTP 请求进入 → 查课程 → Lua 扣 Redis → 生成订单号 → 投消息 → 立刻返回 PENDING
                                                        ↓
                                              （后台线程，与用户无关）
                                              扣 MySQL → 改状态 → 落库
```

同一个 `POST /api/seckill/88`，两个版本的返回值长这样：

```jsonc
// v2：200 且有订单号 = 真的选上了
{"code":200,"message":"操作成功","data":{"orderNo":"179464584731600015783","status":1}}

// v3：200 且有订单号 = 请求进队列了，还没选上
{"code":200,"message":"操作成功","data":{"orderNo":"179464584731600015783","status":0}}
```

**这个变化必须写进接口文档**，否则前端会以为 200 就是成功，
用户看到"选课成功"两秒后刷新发现课没了。

### 2.1 为什么不能让 status 一直是 0

如果消费者挂了，订单会永远停在 `status=0`（待处理）。
所以必须有**兜底**：PENDING 超过一定时间还没变状态，就是异常，要能被找出来。

本阶段不做自动清扫（§17），但**你必须能在压测后一条 SQL 找出它们**：

```sql
SELECT COUNT(*) FROM seckill_order
WHERE status = 0 AND create_time < NOW() - INTERVAL 30 SECOND;
```

正常应该是 0。如果不是 0，说明有消息丢了或消费失败没进死信（§4）。

---

## §3 目标架构

### 3.1 完整链路

```
                    ┌─────────────────────────────────────────────┐
POST /api/seckill/88│  SeckillServiceImplV3MqAsync                │
  (Tomcat 线程)     │                                             │
      │             │  1. 查课程 + 校验时间窗口        → MySQL    │
      │             │  2. Lua 扣 Redis 库存            → Redis    │  ~0.2ms
      │             │     （200 个请求在这里就被挡回，不进 MQ）    │
      │             │  3. 查是否已有订单 / INSERT PENDING → MySQL │  ~1ms
      │             │  4. 发消息 + 等 publisher confirm → MQ      │  ~2ms
      │             │  5. 返回 {orderNo, status:0}                │
      └────────────►│                                             │
                    └─────────────────────────────────────────────┘
                                       │
                                       ▼  seckill.order.exchange / seckill.order
                    ┌─────────────────────────────────────────────┐
                    │  seckill.order.queue（durable + DLX）        │
                    └─────────────────────────────────────────────┘
                                       │
                                       ▼
                    ┌─────────────────────────────────────────────┐
                    │  SeckillOrderConsumer（manual ack）          │
                    │                                             │
                    │  1. INSERT mq_consume_log(msg_id)  → 幂等闸  │
                    │  2. UPDATE course_stock 扣减        → 竞争中 │
                    │  3. UPDATE seckill_order 0 → 1              │
                    │  4. 事务提交 → basicAck                     │
                    │                                             │
                    │  异常 → basicNack(requeue=false) → DLQ      │
                    └─────────────────────────────────────────────┘
```

### 3.2 为什么"扣库存"留在 MySQL 而不是只扣 Redis

有人会问：Redis 都已经扣了，为什么消费者还要扣一次 MySQL？

因为 **Redis 不是账本**（v2 报告 §9 已写）：
Memurai `appendonly=no`，进程一挂数据全丢。真正的账只有 MySQL 那张 `course_stock`。

所以正确的关系是：

| 存储 | 角色 | 挂了会怎样 |
|---|---|---|
| Redis | **门禁**：快速拒绝明显抢不到的请求 | 库存归零，但可以预热重建 |
| MySQL | **账本**：唯一可信的库存和订单 | 就真没了，必须靠 binlog 恢复 |
| `mq_consume_log` | **幂等闸**：挡住重复消息 | 可能重复扣库存 |

### 3.3 那 v3 到底快在哪

对比一下每个请求干了什么：

| 版本 | HTTP 线程做的事 | 是否被同一行锁住 |
|---|---|---|
| v2 | Lua 扣 Redis → **UPDATE 库存（排队）** → 查重 → INSERT | ✅ 被锁 |
| v3 | Lua 扣 Redis → INSERT 订单（不同行，不排队）→ 发消息 | ❌ **不被锁** |

关键洞察：

- **`UPDATE course_stock WHERE course_id=88`** 才是排队的地方（100 个请求抢同一行）
- **`INSERT seckill_order`** 不排队（每个学生插自己那行，`uk_student_course` 索引也不冲突）

v3 把**会排队的那条 SQL** 移到消费者线程。HTTP 线程只剩"不会排队"的操作。

⚠️ 但注意：消费者仍然是串行扣同一行。那 100 次 UPDATE 还是要花约 200ms，
只不过这 200ms 不在用户等待的路径上了。**这才是"削峰"的真实含义**：
不是让事情变快，是让用户不等。

---

## §4 消息可靠性三问（本阶段核心）

这一节是 v3 的全部难度。代码量不大，但如果这三问答不上来，
面试官问一句"消息丢了怎么办"就露馅了。

### 4.1 第一问：消息会丢吗？——会，在三个位置

| 位置 | 丢失场景 | 防御手段 |
|---|---|---|
| **① 生产者 → broker** | `convertAndSend` 返回了，但 broker 没收到（网络断、broker 正忙） | **publisher confirm** |
| **② broker 自己** | broker 收到消息放在内存里，还没写盘就重启 | **队列 durable + 消息 persistent** |
| **③ broker → 消费者** | 消费者拿到消息，处理到一半进程被杀 | **手动 ack（处理完才 ack）** |

#### ① 生产者 → broker：publisher confirm

默认情况下 `rabbitTemplate.convertAndSend()` 是**发出去就不管了**——
它只是把字节写进 TCP socket，broker 有没有真的收下，它不知道。

开启 confirm 后，broker 会回一个 ack/nack，你才能知道：

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated   # 每个消息单独回调
    publisher-returns: true              # 路由不到队列时退回（另一个问题，见坑 10）
```

代码侧要用 `CorrelationData`：

```java
CorrelationData cd = new CorrelationData(msg.msgId());
rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, msg, cd);

// 等 broker 确认（这一步会让发布变慢，但换来"知道有没有发成功"）
CorrelationData.Confirm confirm = cd.getFuture().get(3, TimeUnit.SECONDS);
if (!confirm.isAck()) {
    throw new BusinessException(ResultCode.INTERNAL_ERROR, "消息队列拒绝接收，请重试");
}
```

**实测数据支持这个代价**：不带 confirm 的发布是 5.2 万 msg/s，
带上 confirm 等于每条多一次往返。本项目 300 条消息，多出来的时间可以忽略。

#### ② broker 自己：durable + persistent

```java
// 队列 durable=true：broker 重启后队列本身还在
QueueBuilder.durable(ORDER_QUEUE)

// 消息默认是 persistent 的（Spring AMQP 的 MessageProperties 默认 deliveryMode=PERSISTENT）
```

⚠️ 两个都要，缺一不可：
- 队列 durable 但消息非持久 → 重启后队列在，消息没了
- 消息持久但队列非 durable → 重启后队列都没了，消息当然也没了

#### ③ broker → 消费者：手动 ack 的时机

这是**最容易写错的地方**，也是本阶段唯一一个"写错了数据就错"的地方。

```java
// ❌ 错误：先 ack 再处理
channel.basicAck(tag, false);
doBusiness(msg);          // 这里抛异常，消息已经被 ack 掉了，永远丢失

// ❌ 错误：处理完 ack，但事务还没提交
transactionTemplate.execute(s -> doBusiness(msg));
channel.basicAck(tag, false);   // 事务在这之后才真正 commit
                                // 如果 commit 失败，消息已 ack，数据没落库

// ✅ 正确：事务提交之后才 ack
transactionTemplate.executeWithoutResult(s -> doBusiness(msg));  // 返回 = 已提交
channel.basicAck(tag, false);
```

**规则一句话：`basicAck` 必须是这条消息所有副作用都已落地之后的最后一步。**

Spring 的 `TransactionTemplate.execute()` 是**同步**的——
方法返回时事务已经提交（或已回滚并抛异常），所以上面的顺序是对的。

### 4.2 第二问：消息会重复吗？——一定会的

AMQP 的投递语义是 **at-least-once（至少一次）**，不是 exactly-once。
以下情况都会导致同一条消息被消费两次：

1. 消费者处理完了，`basicAck` 在网络上丢了 → broker 认为没消费，重投
2. 消费者处理了一半进程被杀 → 消息未 ack，重投
3. broker 主从切换（本机没有从库，但生产环境有）
4. 你自己在代码里 `basicNack(requeue=true)` 重试

**你无法阻止重复，只能让重复无害。** 这就是 §5 的幂等设计。

⚠️ 特别注意：`basicNack(requeue=true)` 是**危险的**。
如果消息本身有问题（比如 JSON 格式错、引用了不存在的课程），
requeue 会让它无限重投，日志刷爆、CPU 打满。
所以本项目**一律 `requeue=false`**，失败就进死信（§6）。

### 4.3 第三问：消费失败了怎么办？——分级处理

不是所有失败都一样。必须区分：

| 失败类型 | 例子 | 处理方式 |
|---|---|---|
| **可重试** | 数据库连接池临时耗尽、死锁 | 重试（有限次），还失败才进死信 |
| **不可重试** | JSON 解析失败、课程 ID 不存在 | **立刻**进死信，重试只会浪费时间 |
| **业务失败** | MySQL 库存已为 0（Redis 与 DB 不一致） | 标记订单 `status=2` 失败，**正常 ack** |

第三类最容易被写错：业务失败**不是**技术异常，
你应该把订单状态改成"失败"并正常 ack，而不是 nack 让它重投。
重投一百次，库存也还是 0。

本阶段的实现策略（够用且不过度设计）：

```java
try {
    transactionTemplate.executeWithoutResult(s -> doBusiness(msg));
    channel.basicAck(tag, false);
} catch (DuplicateMessageException e) {
    // 已经处理过了，直接 ack（幂等闸挡住的，不是错误）
    channel.basicAck(tag, false);
} catch (Exception e) {
    log.error("消费失败，消息进死信 msgId={} orderNo={}", msg.msgId(), msg.orderNo(), e);
    // requeue=false：不重投，直接进 DLQ，人工看
    channel.basicNack(tag, false, false);
}
```

⚠️ **注意**：`requeue=false` 且队列**没有配 DLX** 时，消息会被**直接丢弃**，
连痕迹都没有。所以 §6 的死信队列不是可选项。

### 4.4 三问总结表（面试可直接背）

| 问题 | 答案 | 本项目的实现 |
|---|---|---|
| 消息会丢吗 | 会，三个位置 | publisher confirm + durable 队列 + 手动 ack |
| 消息会重复吗 | 一定会（至少一次投递） | `mq_consume_log.msg_id` 唯一索引 |
| 消费失败怎么办 | 分级：重试 / 死信 / 标失败 | `basicNack(requeue=false)` → DLQ |
| 为什么不用 requeue | 毒消息会无限重投 | 一律 false，失败进 DLQ |
| 为什么 ack 要最后 | ack 后崩溃 = 消息永久丢失 | ack 在事务提交之后 |

---

## §5 幂等设计

### 5.1 为什么不用"查订单表"做幂等

第一反应是：消费前先查订单是否已存在，存在就跳过。**不行**，两个原因：

1. 订单在**生产者**就插入 PENDING 了（§3.1 第 3 步），消费者执行时订单**一定已存在**
   —— 这个检查永远返回"已存在"，等于关掉了整个消费逻辑
2. 就算订单不在生产者插入，`SELECT` + `INSERT` 之间仍有竞态窗口

所以幂等闸必须挂在一个**专门为它存在的表**上：`mq_consume_log`。

### 5.2 实现：INSERT 撞唯一索引

```java
// 事务内的第一步
MqConsumeLog logRow = new MqConsumeLog();
logRow.setMsgId(msg.msgId());       // uk_msg_id 唯一索引
logRow.setStatus(0);
try {
    consumeLogMapper.insert(logRow);
} catch (DuplicateKeyException e) {
    // 这条消息处理过了 —— 这就是幂等的全部机制
    throw new DuplicateMessageException(msg.msgId());
}
```

**为什么这样是对的**：

- 第一次消费：INSERT 成功 → 继续扣库存 → 改订单状态 → 事务提交 → ack
- 重复消费：INSERT 撞 `uk_msg_id` → 抛异常 → 事务回滚 → 我们 ack 掉它

**关键**：INSERT 必须和后续业务操作**在同一个事务里**。
如果 INSERT 单独提交了，然后业务失败回滚，那这条消息就永远无法重试了
（下次来会被幂等闸挡掉）。这正是《项目开发文档》§5.4 强调的"去重表 INSERT 与业务必须同事务"。

### 5.3 msgId 从哪来

生产者生成，用 UUID：

```java
String msgId = UUID.randomUUID().toString();   // 36 字符，varchar(64) 够
```

⚠️ 不要用 `orderNo` 当 `msgId`。虽然本项目一个订单只发一条消息，
但"消息标识"和"业务标识"是两个概念。如果以后一个订单要发"下单"和"取消"两条消息，
用 orderNo 会撞唯一索引。**保持它们独立**。

### 5.4 幂等闸的副产品：可观测性

`mq_consume_log.status` 有三个值：

| 值 | 含义 |
|---|---|
| 0 | 正在处理（事务内，外界看不到） |
| 1 | 处理成功 |
| 2 | 业务失败（库存不足等），已标记订单失败 |

压测后查一下就能知道消费者干了什么：

```sql
SELECT status, COUNT(*) FROM seckill.mq_consume_log GROUP BY status;
```

正常应该 `1` 的数量 = 成功订单数。

---

## §6 死信队列

### 6.1 消息怎么进死信

三种情况（RabbitMQ 的规则，不是我们写的）：

1. 消息被 `basicNack(requeue=false)` 或 `basicReject(requeue=false)`
2. 消息 TTL 过期
3. 队列长度超过 `x-max-length`

本项目的 `basicNack(tag, false, false)` 走第 1 条。

### 6.2 声明方式

死信不是"一个特殊队列"，而是**普通队列上挂了两个参数**：

```java
// 业务队列：声明时告诉 broker "我死了往哪送"
@Bean
public Queue orderQueue() {
    return QueueBuilder.durable(RabbitConfig.ORDER_QUEUE)
            .deadLetterExchange(RabbitConfig.DLX_EXCHANGE)
            .deadLetterRoutingKey(RabbitConfig.DLQ_ROUTING_KEY)
            .build();
}

// 死信交换机 + 死信队列：就是普通的 exchange 和 queue
@Bean
public DirectExchange dlxExchange() {
    return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
}

@Bean
public Queue dlqQueue() {
    return QueueBuilder.durable(DLQ_QUEUE).build();
}

@Bean
public Binding dlqBinding() {
    return BindingBuilder.bind(dlqQueue()).to(dlxExchange()).with(DLQ_ROUTING_KEY);
}
```

### 6.3 ⚠️ 致命细节：队列参数一旦声明就不能改

RabbitMQ 的规则：**队列已存在时，用不同参数再次声明会报错**：

```
PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange' for queue
'seckill.order.queue' in vhost '/': received 'seckill.dlx.exchange' but current is ''
```

所以如果你第一次声明队列时忘了加 DLX，之后想加，**必须先删队列**：

```powershell
# 管理台的方式（推荐，先看有没有消息）
curl.exe -s "http://localhost:15672/api/queues/%2F/seckill.order.queue" -u "seckill:$env:RABBITMQ_PASSWORD"

# 删除（会丢消息，开发期无所谓）
curl.exe -s -X DELETE "http://localhost:15672/api/queues/%2F/seckill.order.queue" -u "seckill:$env:RABBITMQ_PASSWORD"
```

**这个坑几乎每个人都会踩一次**。见 §16 第 6 条。

### 6.4 怎么知道死信里有东西

```powershell
curl.exe -s "http://localhost:15672/api/queues/%2F/seckill.order.dlq" -u "seckill:$env:RABBITMQ_PASSWORD" |
  ConvertFrom-Json | Select-Object name, messages
```

或管理台 <http://localhost:15672> → Queues → `seckill.order.dlq`。

⚠️ 死信队列里**正常应该是 0 条**。有东西说明有消费失败，必须查日志。

---

## §7 接口契约变更

### 7.1 三个接口的语义

| 接口 | v2 | v3 |
|---|---|---|
| `POST /api/seckill/{courseId}` | 200 = 选上了（status=1） | 200 = **已排队**（status=0） |
| `POST /api/seckill/{courseId}/cancel` | 同步退课 | 仍然同步（本阶段不改） |
| `GET /api/order/{orderNo}` | 查结果 | 查结果（**轮询用**） |

### 7.2 返回体

```jsonc
// 成功进队列
{"code":200,"message":"操作成功","data":{"orderNo":"179464584731600015783","status":0}}

// 名额已满（Redis 挡住的，一次数据库没碰）
{"code":409,"message":"该课程名额已满","data":null}

// 已选过
{"code":409,"message":"你已选过该课程","data":null}

// 队列不可用（confirm 超时或 nack）
{"code":500,"message":"系统繁忙，请重试","data":null}
```

### 7.3 客户端轮询策略

这是前端要配合的部分，写进文档免得以后自己都忘了：

```
1. POST 抢课 → 拿到 orderNo
2. 立刻 GET /api/order/{orderNo}
   - status=1 → 成功，显示"选课成功"
   - status=2 → 失败，显示 failReason
   - status=0 → 继续轮询
3. 轮询间隔建议：200ms 起步，每次 ×1.5，最多 5 次（总约 2 秒）
4. 5 次还是 0 → 显示"处理中，请稍后在"我的选课"查看"
```

⚠️ **不要**在服务端做"等结果再返回"（§0.2）。轮询是客户端的责任。

### 7.4 本阶段的 `cancel` 不做异步

原因：退课的量极小（用户手动点），没有削峰需求。
而且退课一旦异步，用户点完看到"待处理"会更困惑。

**v3 只改抢课，退课保持 v2 的同步实现。** 这是"一次只改一个变量"（P3）。

---

## §8 逐文件实现要点

### 8.1 文件清单

**新建（6 个）**

| 文件 | 类型 | 说明 |
|---|---|---|
| `infra/mq/SeckillMessage.java` | 基础设施 | 消息体，record |
| `config/RabbitConfig.java` | 基础设施 | 交换机/队列/绑定/转换器 |
| `infra/mq/SeckillMessageProducer.java` | 基础设施 | 发消息 + 等 confirm |
| `infra/mq/SeckillOrderConsumer.java` | **业务** | **消费者，本阶段重点** |
| `entity/MqConsumeLog.java` | 基础设施 | 幂等表实体 |
| `mapper/MqConsumeLogMapper.java` | 基础设施 | 就一个 BaseMapper |
| `service/impl/SeckillServiceImplV3MqAsync.java` | **业务** | **生产者，本阶段重点** |

**修改（2 个）**

| 文件 | 改什么 |
|---|---|
| `mapper/SeckillOrderMapper.java` | 加一个按 orderNo 改状态的方法 |
| `src/main/resources/application.yaml` | 加 confirm 配置、`default-requeue-rejected: false` |

**不动**：`OrderController`、`OrderServiceImpl`、`SeckillController`、v0~v2 的所有实现。

**分工建议**：标"基础设施"的可以直接照抄（它们没有业务判断，只有配置）；
标"业务"的两个请自己写，写之前先想清楚 §4 的三问。

---

### 8.2 `SeckillMessage.java`

```java
package com.example.seckill.infra.mq;

/**
 * 抢课消息体。
 *
 * <p>用 record 而不是 @Data 类：消息体一旦发出就不该被修改，
 * record 的不可变性在编译期就挡住了 setter。
 *
 * <p>字段尽量少 —— 消息越大，broker 内存涨得越快（§1.3 实测每条 196 字节）。
 * 消费者需要的其他数据（课程名等）自己去数据库查。
 */
public record SeckillMessage(
        String msgId,      // 幂等键，UUID，不用 orderNo
        String orderNo,    // 生产者已插入的 PENDING 订单号
        Long studentId,
        Long courseId
) {
}
```

---

### 8.3 `RabbitConfig.java`

```java
package com.example.seckill.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "seckill.order.exchange";
    public static final String ORDER_QUEUE = "seckill.order.queue";
    public static final String ORDER_ROUTING_KEY = "seckill.order";

    public static final String DLX_EXCHANGE = "seckill.dlx.exchange";
    public static final String DLQ_QUEUE = "seckill.order.dlq";
    public static final String DLQ_ROUTING_KEY = "seckill.order.dlq";

    @Bean
    public DirectExchange orderExchange() {
        return ExchangeBuilder.directExchange(ORDER_EXCHANGE).durable(true).build();
    }

    /** 业务队列：挂了死信参数（§6.2） */
    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(ORDER_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding orderBinding() {
        return BindingBuilder.bind(orderQueue()).to(orderExchange()).with(ORDER_ROUTING_KEY);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(dlqQueue()).to(dlxExchange()).with(DLQ_ROUTING_KEY);
    }

    /**
     * ⚠️ 必须是 JacksonJsonMessageConverter（Jackson 3），
     * 不是 Jackson2JsonMessageConverter（Jackson 2，本项目没有对应 Bean）。见 §1.5。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
```

**要点**：

- Spring Boot 的 `RabbitAutoConfiguration` 会自动创建一个 `RabbitAdmin`，
  它会在**第一次建立连接时**把这里声明的 exchange/queue/binding 全部建出来。
  所以你**不需要**手写 `rabbitAdmin.declareXxx()`。
- Bean 名 `jsonMessageConverter` 会被自动注入到 `RabbitTemplate` 和
  `SimpleRabbitListenerContainerFactory`，不需要手动设置。
- 声明顺序不重要，Spring 容器会处理依赖。

---

### 8.4 `SeckillMessageProducer.java`

```java
package com.example.seckill.infra.mq;

import com.example.seckill.common.ResultCode;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillMessageProducer {

    private static final long CONFIRM_TIMEOUT_SECONDS = 3;

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送抢课消息，并等待 broker 确认。
     *
     * <p>抛异常 = 消息没进去。调用方必须做补偿（还 Redis 库存、标订单失败）。
     * 绝不允许"发不出去也当成功" —— 那样订单会永远停在待处理。
     */
    public void send(SeckillMessage message) {
        CorrelationData correlation = new CorrelationData(message.msgId());
        rabbitTemplate.convertAndSend(
                RabbitConfig.ORDER_EXCHANGE,
                RabbitConfig.ORDER_ROUTING_KEY,
                message,
                correlation);

        try {
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                log.error("broker 拒绝接收消息 msgId={} reason={}",
                        message.msgId(), confirm.getReason());
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "系统繁忙，请重试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("等待 publisher confirm 失败 msgId={}", message.msgId(), e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "系统繁忙，请重试");
        }
    }
}
```

**要点**：

- `CorrelationData.getFuture()` 返回 `CompletableFuture<Confirm>`，
  必须 `.get(timeout)` 加超时，否则 broker 不回话时线程会挂死。
- 超时时间 3 秒不是随便定的：它决定了"队列出问题时用户等多久才看到错误"。
- ⚠️ 只有开了 `publisher-confirm-type` 这个 future 才会完成；没开的话会一直等到超时。

---

### 8.5 `SeckillOrderConsumer.java`（本阶段重点）

结构：

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillOrderConsumer {

    private final MqConsumeLogMapper consumeLogMapper;
    private final CourseStockMapper courseStockMapper;
    private final SeckillOrderMapper orderMapper;
    private final TransactionTemplate transactionTemplate;

    @RabbitListener(queues = RabbitConfig.ORDER_QUEUE)
    public void onMessage(SeckillMessage message,
                          org.springframework.amqp.core.Message raw,
                          com.rabbitmq.client.Channel channel) throws java.io.IOException {

        long deliveryTag = raw.getMessageProperties().getDeliveryTag();
        log.info("收到消息 msgId={} orderNo={}", message.msgId(), message.orderNo());

        try {
            transactionTemplate.executeWithoutResult(status -> handle(message));
            channel.basicAck(deliveryTag, false);        // ← 事务已提交，最后才 ack

        } catch (DuplicateMessageException e) {
            // 幂等闸挡住的重复消息：不是错误，正常 ack
            log.warn("重复消息，已忽略 msgId={}", message.msgId());
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("消费失败，进死信 msgId={} orderNo={}",
                    message.msgId(), message.orderNo(), e);
            channel.basicNack(deliveryTag, false, false);  // requeue=false → DLQ
        }
    }

    /** 在事务内执行；抛异常 = 整个事务回滚 */
    private void handle(SeckillMessage message) {
        // ---------- 第 1 步：幂等闸 ----------
        MqConsumeLog logRow = new MqConsumeLog();
        logRow.setMsgId(message.msgId());
        logRow.setStatus(0);
        try {
            consumeLogMapper.insert(logRow);
        } catch (DuplicateKeyException e) {
            throw new DuplicateMessageException(message.msgId());
        }

        // ---------- 第 2 步：扣 MySQL 库存（会在这里排队，但不在 HTTP 路径上）----------
        int affected = courseStockMapper.tryDecrement(message.courseId());
        if (affected == 0) {
            // Redis 说还有、MySQL 说没了 —— 两边不一致。
            // 这是"业务失败"，不是"技术异常"：标订单失败并正常 ack，不要 nack 重投
            log.warn("MySQL 库存不足（Redis 与 DB 不一致）orderNo={} courseId={}",
                    message.orderNo(), message.courseId());
            orderMapper.updateStatusAndFailReason(
                    message.orderNo(), SeckillOrder.STATUS_FAILED, "库存不足");
            updateLogStatus(message.msgId(), 2, "库存不足");
            return;
        }

        // ---------- 第 3 步：订单 PENDING → SUCCESS ----------
        int updated = orderMapper.updateStatusByOrderNo(
                message.orderNo(), SeckillOrder.STATUS_SUCCESS);
        if (updated == 0) {
            // 订单不是 PENDING（可能已被取消/已失败），这是异常情况但不是技术错误
            log.warn("订单状态不是待处理，跳过 orderNo={}", message.orderNo());
        }

        // ---------- 第 4 步：幂等日志标成功 ----------
        updateLogStatus(message.msgId(), 1, null);
    }
}
```

**必须自己补的部分**：

1. `DuplicateMessageException`（可以放 `exception/` 包，继承 `RuntimeException`）
2. `updateLogStatus(String msgId, int status, String errorMsg)` —— 一条 `UPDATE` 就行
3. `orderMapper.updateStatusByOrderNo(String orderNo, Integer newStatus)`
4. `orderMapper.updateStatusAndFailReason(String orderNo, Integer newStatus, String failReason)`

**⚠️ 两个必须想清楚的点**：

1. **为什么 `handle()` 里没有 `@Transactional`？**
   因为它是 `private` 方法，Spring AOP 代理拦不住内部调用（§16 第 5 条）。
   事务边界由外层的 `transactionTemplate` 手动控制。

2. **为什么第 3 步的 `updated == 0` 不算失败？**
   因为订单可能是用户刚退课（status=3）。这时硬报错会让消息进死信，
   而其实什么坏事都没发生。**区分"数据不符合预期"和"操作失败"**。

---

### 8.6 `SeckillServiceImplV3MqAsync.java`（本阶段重点）

```java
@Service
@AllArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "seckill.strategy", havingValue = "v3-mq-async")
public class SeckillServiceImplV3MqAsync implements SeckillService {

    private final CourseMapper courseMapper;
    private final CourseStockMapper courseStockMapper;
    private final SeckillOrderMapper orderMapper;
    private final StockCacheService stockCacheService;
    private final SeckillMessageProducer producer;
    private final TransactionTemplate transactionTemplate;

    @Override
    public SeckillResponse seckill(Long courseId) {
        // 1~2 步与 v2 完全一样：查课程、Lua 扣 Redis
        //    （200 个抢不到的请求在这里就返回 409，一次 MySQL 都没碰，也不会发消息）
        ...
        Long studentId = (Long) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        Long left = stockCacheService.tryDecrement(courseId);
        // ... 预热重试、-2 抛名额已满、<0 抛异常（同 v2）

        // 3. 写 PENDING 订单（自己的小事务）
        boolean enqueued = false;
        try {
            SeckillOrder pending = transactionTemplate.execute(status -> {
                SeckillOrder exist = orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getStudentId, studentId)
                        .eq(SeckillOrder::getCourseId, courseId));

                if (exist != null) {
                    if (exist.getStatus() == SeckillOrder.STATUS_SUCCESS
                            || exist.getStatus() == SeckillOrder.STATUS_PENDING) {
                        throw new BusinessException(ResultCode.CONFLICT, "你已选过该课程");
                    }
                    if (exist.getStatus() == SeckillOrder.STATUS_CANCELLED) {
                        // R5：退课后重选，复用旧订单号，改回待处理
                        exist.setStatus(SeckillOrder.STATUS_PENDING);
                        exist.setFailReason(null);
                        orderMapper.updateById(exist);
                        return exist;
                    }
                }

                SeckillOrder created = SeckillOrder.builder()
                        .orderNo(generateOrderNo(studentId))
                        .studentId(studentId)
                        .courseId(courseId)
                        .status(SeckillOrder.STATUS_PENDING)   // ← 0，不是 1
                        .build();
                orderMapper.insert(created);
                return created;
            });

            // 4. 发消息（confirm 通过才算成功）
            String msgId = UUID.randomUUID().toString();
            producer.send(new SeckillMessage(
                    msgId, pending.getOrderNo(), studentId, courseId));
            enqueued = true;

            // 5. 立刻返回"已排队"
            return new SeckillResponse(pending.getOrderNo(), SeckillOrder.STATUS_PENDING);

        } finally {
            if (!enqueued) {
                // 任何一步失败：Redis 归还 + 订单标记失败
                try {
                    stockCacheService.increment(courseId);
                } catch (Exception e) {
                    log.error("Redis 库存归还失败，需对账 courseId={} studentId={}",
                            courseId, studentId, e);
                }
                // ⚠️ 别忘了把那张 PENDING 订单也处理掉，否则它永远停在 0
            }
        }
    }

    @Override
    public SeckillResponse cancel(Long courseId) {
        throw new UnsupportedOperationException("v3 阶段退课仍用 v2 实现");
    }
}
```

**必须自己想清楚的部分**：

1. **`finally` 里怎么处理那张 PENDING 订单？**
   选项 A：删掉；选项 B：改成 `status=2` 失败（带 `failReason`）。
   想一下哪个更好——如果用户轮询到了它，看到"待处理"和看到"失败：系统繁忙"，
   哪个体验更合理？**哪个更容易被误判为"我选上了"？**

2. **`generateOrderNo` 怎么来的？** 照抄 v0~v2 的（时间戳 + 学生 ID 后四位 + 随机四位）。

3. **为什么订单 INSERT 和发消息不能放在同一个事务里？**
   因为 RabbitMQ 不是数据库，参与不了本地事务。
   如果放在同一个事务：事务提交前发消息 → 消费者可能先看到消息、
   但订单还没提交 → 消费者查不到订单。这就是"分布式事务"问题，
   本阶段的取舍见 §17（用"PENDING 订单 + confirm + 失败补偿"代替两阶段提交）。

---

### 8.7 `SeckillOrderMapper` 要加的方法

```java
/**
 * 按订单号改状态（消费者用）。
 * 加 AND status = 0 是防御：只允许"待处理 → 成功"，不能把已退课的订单改成成功。
 */
@Update("UPDATE seckill_order SET status = #{newStatus} " +
        "WHERE order_no = #{orderNo} AND status = 0")
int updateStatusByOrderNo(@Param("orderNo") String orderNo,
                          @Param("newStatus") Integer newStatus);

@Update("UPDATE seckill_order " +
        "SET status = #{newStatus}, fail_reason = #{failReason} " +
        "WHERE order_no = #{orderNo} AND status = 0")
int updateStatusAndFailReason(@Param("orderNo") String orderNo,
                              @Param("newStatus") Integer newStatus,
                              @Param("failReason") String failReason);
```

⚠️ 不要用 `updateById` 来做这件事——那是"先查再改"，中间有竞态窗口。
条件更新（`WHERE status = 0`）才是原子的。

---

### 8.8 `MqConsumeLog.java` + `MqConsumeLogMapper.java`

```java
@TableName("mq_consume_log")
@Data
public class MqConsumeLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String msgId;
    private Integer status;
    private String errorMsg;
    private LocalDateTime createTime;
}
```

```java
public interface MqConsumeLogMapper extends BaseMapper<MqConsumeLog> {
}
```

---

## §9 配置变更

`src/main/resources/application.yaml` 的 `spring.rabbitmq` 段改为：

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: ${RABBITMQ_USER:seckill}
    password: ${RABBITMQ_PASSWORD:}

    # 生产者确认：broker 收到消息后回 ack/nack（§4.1 ①）
    publisher-confirm-type: correlated
    # 消息路由不到队列时退回给生产者（防"交换机对了但 routing key 写错"）
    publisher-returns: true

    listener:
      simple:
        # 手动 ack 是可靠性的前提（§4.1 ③）
        acknowledge-mode: manual
        concurrency: 2
        max-concurrency: 8
        prefetch: 10
        # ⚠️ 默认是 true：消费方法抛异常时把消息放回队列
        #    那会导致毒消息无限重投，日志刷爆。必须改成 false，失败进死信（§6）
        default-requeue-rejected: false
```

**启动 v3**：

```powershell
.\mvnw.cmd -s "D:\develop\apache-maven-3.9.15\conf\settings.xml" -B -ntp -o `
  "-Daether.syncContext.named.factory=noop" spring-boot:run `
  "-Dspring-boot.run.arguments=--seckill.strategy=v3-mq-async"
```

⚠️ 别忘了 `$env:DB_PASSWORD` 和 `$env:RABBITMQ_PASSWORD` 两个环境变量
（`seckill-rabbitmq` 的密码在 `application-local.yaml` 里，不要写进 yaml 主文件）。

---

## §10 单元测试：能测什么，测不了什么

### 10.1 能测的（值得写）

| 测什么 | 怎么测 |
|---|---|
| 消费者：重复 msgId 走 ack 分支 | mock `consumeLogMapper.insert` 抛 `DuplicateKeyException`，断言不抛异常 |
| 消费者：库存为 0 时订单标失败 | mock `tryDecrement` 返回 0，verify 调用了 `updateStatusAndFailReason` |
| 生产者：confirm=nack 时抛异常 | mock `CorrelationData` 的 future 很难，**可以跳过这个** |
| 服务层：订单初始状态是 PENDING | mock 各 mapper，断言 `SeckillResponse.getStatus() == 0` |

### 10.2 测不了的（别浪费时间）

| 测不了什么 | 为什么 |
|---|---|
| ack 时机对不对 | Mockito 里没有真的 `Channel`，也不会真的提交事务 |
| 消息真的进队列了吗 | 需要真 broker |
| 幂等真的生效吗 | 需要真的唯一索引冲突 |
| 事务真的回滚了吗 | 需要真的数据库 |

**结论：v3 的核心正确性（ack 时机、幂等、事务）单测一律测不到，
必须靠 §13 的实机自测。** 这和 v1 的教训一样（任务书 §9：单测证不了事务）。

Mockito Extension 补：**别忘了 `@BeforeAll static void initTableInfo()` 注册新实体**
`MqConsumeLog`，否则会踩 `can not find lambda cache for this entity`（阶段 1 的老坑）。

---

## §11 压测方案（数成功单的方法变了）

### 11.1 为什么 k6 脚本必须改

v2 的脚本这样数成功：

```javascript
if (res.status === 200 && res.body.code === 200) bizSuccess.add(1);
```

v3 里这行**完全失效**——200 只代表"进队列了"。
300 个请求可能全部返回 200，但实际只有 100 个能成功。

**所以 `biz_success` 的含义必须改：**

```javascript
const bizQueued   = new Counter('biz_queued');    // 200 + status=0，进队列了
const bizSoldOut  = new Counter('biz_sold_out');  // 409 名额已满
const bizDuplicate= new Counter('biz_duplicate'); // 409 已选过
const bizRejected = new Counter('biz_rejected');  // 500，队列不可用
```

**真正的成功单数只能压测后查数据库**：

```sql
SELECT COUNT(*) FROM seckill.seckill_order WHERE course_id = 88 AND status = 1;
```

### 11.2 新增：最终一致延迟

这是 v3 独有的指标——**从最后一个请求返回，到所有订单都变成 status=1，花了多久**。

```powershell
# 压测结束后立刻跑，记录耗时
$sw = [System.Diagnostics.Stopwatch]::StartNew()
do {
    Start-Sleep -Milliseconds 50
    $pending = (mysql -e "SELECT COUNT(*) FROM seckill.seckill_order WHERE status = 0" -N)
} while ($pending -gt 0 -and $sw.Elapsed.TotalSeconds -lt 30)
$sw.Stop()
Write-Output ("最终一致耗时: " + [math]::Round($sw.Elapsed.TotalMilliseconds) + " ms，残留 PENDING=" + $pending)
```

预期：**几百毫秒内**（100 条消息，消费者 2 线程，每条 ~2ms）。

### 11.3 压测步骤（每轮都做）

```powershell
# 1. 复位（含清队列和死信）
.\loadtest\reset.ps1 -CourseId 88
# 里面要加：
#   $redis.del("seckill:stock:88")
#   curl DELETE /api/queues/%2F/seckill.order.queue
#   curl DELETE /api/queues/%2F/seckill.order.dlq
#   （删队列是为了清空消息；Spring 启动时会自动重新声明）

# 2. 预热
curl.exe -s -X POST "http://localhost:8080/api/admin/cache/stock/88" -H "Authorization: Bearer $adminToken"

# 3. 压测
k6 run -e VUS=300 -e STUDENT_COUNT=300 .\loadtest\seckill-single.js

# 4. 立刻测最终一致延迟（上面的循环）
# 5. 判定
```

### 11.4 判定 SQL（v3 版）

```sql
-- ① 库存守恒
SELECT course_id, capacity, stock, sold, stock + sold AS 守恒
FROM seckill.course_stock WHERE course_id = 88;

-- ② 成功单数（v3 唯一有效的成功口径）
SELECT COUNT(*) AS success_orders FROM seckill.seckill_order
WHERE course_id = 88 AND status = 1;

-- ③ 超卖
SELECT COUNT(*) - 100 AS oversold_by FROM seckill.seckill_order
WHERE course_id = 88 AND status = 1;

-- ④ 残留 PENDING（必须是 0，不是 0 说明有消息丢了/消费失败）
SELECT COUNT(*) AS stuck_pending FROM seckill.seckill_order
WHERE course_id = 88 AND status = 0;

-- ⑤ 失败单（业务失败，应该很少）
SELECT COUNT(*) AS failed_orders FROM seckill.seckill_order
WHERE course_id = 88 AND status = 2;

-- ⑥ 同学生多成功单（必须是 0）
SELECT student_id, COUNT(*) c FROM seckill.seckill_order
WHERE course_id = 88 AND status = 1 GROUP BY student_id HAVING c > 1;

-- ⑦ 幂等日志
SELECT status, COUNT(*) FROM seckill.mq_consume_log GROUP BY status;
```

**五条判据**：

| # | 判据 | 期望 |
|---|---|---|
| 1 | `success_orders` | **恰好 100**（不是 ≥100，也不能 <100） |
| 2 | `stock + sold` | **恰好 100** |
| 3 | `oversold_by` | **0** |
| 4 | `stuck_pending` | **0** |
| 5 | 死信队列 | **0 条** |

⚠️ 第 1 条是 v3 特有的**双向**判据。v0~v2 只防"多了"（超卖）；
v3 还要防"少了"——消息丢了、消费失败、死信，都会让成功单数 < 100。
**"少了"比"多了"更难发现**，因为库存账目看起来完全正常。

---

## §12 预期 vs 实测

**先填预测，压测后再填实测。差的偏差比数字本身更值钱。**

| 指标 | 预测 | 实测 | 偏差 |
|---|---|---|---|
| HTTP avg | 15 ~ 50 ms | | |
| HTTP P95 | 50 ~ 150 ms | | |
| HTTP P99 | 150 ~ 300 ms | | |
| `biz_queued` | 100 | | |
| `biz_sold_out` | 200 | | |
| `biz_rejected`（500） | 0 | | |
| 最终一致耗时 | < 1000 ms | | |
| `success_orders` | 100 | | |
| `stuck_pending` | 0 | | |
| 死信条数 | 0 | | |

### 预测依据（写下来，不然事后会自我安慰）

- **P95 目标 50~150ms**：v2 的 255.6ms 里约 200ms 是行锁排队，
  这段被移走后剩下 Redis（0.2ms）+ INSERT（1ms）+ publish+confirm（2ms）≈ 3ms 纯计算。
  但 300 个请求同时砸向 200 个 Tomcat 线程，会有线程排队，
  加上 Hikari 50 连接、100 个请求需要 DB，所以 P95 不可能低到 5ms。
- **为什么可能反而更差**：如果 confirm 的往返在本机也要 2~3ms，
  100 个请求都等 confirm，可能把 P95 拉到 200ms 以上。
  **如果实测超过 v2，说明 confirm 是瓶颈，这个发现比"变快了"更有价值。**
- **`biz_sold_out` 期望 200**：Redis 门禁在发消息之前，所以抢不到的请求不会产生消息。

---

## §13 自测清单（14 项）

每一项都要**看到证据**，不是"应该没问题"。

### 13.1 基础连通（1~3）

- [ ] **1. 队列真的建出来了**
      启动应用后管理台 Queues 页能看到 `seckill.order.queue` 和 `seckill.order.dlq`，
      前者 Arguments 里有 `x-dead-letter-exchange = seckill.dlx.exchange`。
      命令行验证：
      ```powershell
      curl.exe -s "http://localhost:15672/api/queues/%2F/seckill.order.queue" -u "seckill:$env:RABBITMQ_PASSWORD"
      ```
      查 `arguments` 字段。

- [ ] **2. 消息真的发出去了**
      抢一次课后，管理台 `seckill.order.queue` 的 message rates 出现一次小幅波动；
      或者压测时看得到 `deliver` 速率。

- [ ] **3. 消费者真的收到了**
      日志里出现 `收到消息 msgId=... orderNo=...`（`@RabbitListener` 加了 log 才有）。

### 13.2 正常路径（4~7）

- [ ] **4. 抢课返回 PENDING**
      `POST /api/seckill/88` → `{"code":200,...,"status":0}`

- [ ] **5. 几百毫秒后变成功**
      `GET /api/order/{orderNo}` → `status:1`、`statusText:"已成功"`
      ```sql
      SELECT order_no, status FROM seckill.seckill_order WHERE order_no = '...';
      ```

- [ ] **6. 库存和订单都对**
      `course_stock` 的 `stock` 减 1、`sold` 加 1，`seckill_order` 多一条 `status=1`

- [ ] **7. 幂等日志有记录**
      `SELECT * FROM seckill.mq_consume_log` → 一条 `status=1`，`msg_id` 是 UUID

### 13.3 拒绝路径（8~10）

- [ ] **8. 名额满了返回 409 且不发消息**
      把 Redis 库存设为 0（`memurai-cli set seckill:stock:88 0`）→ 抢课 → 409「该课程名额已满」，
      **队列深度不变**（关键：门禁在发消息之前）

- [ ] **9. 重复抢返回 409**
      同一学生第二次 → 409「你已选过该课程」，队列深度不变

- [ ] **10. 退课后重选能复用订单**
      退课（status=3）→ 再抢 → 返回**同一个 orderNo**，最终 status=1，订单表仍只有 1 条

### 13.4 可靠性（11~14，最容易翻车）

- [ ] **11. 幂等真的生效（重复投递不出错）**
      手工把同一条消息再发一次：
      ```powershell
      # 先从 DLQ 或日志里拿到一条完整的消息 JSON，然后：
      curl.exe -s -u "seckill:$env:RABBITMQ_PASSWORD" -H "Content-Type: application/json" `
        -X POST "http://localhost:15672/api/exchanges/%2F/seckill.order.exchange/publish" `
        -d '{\"properties\":{},\"routing_key\":\"seckill.order\",\"payload\":\"{\\\"msgId\\\":\\\"<原msgId>\\\",\\\"orderNo\\\":\\\"<原orderNo>\\\",\\\"studentId\\\":1,\\\"courseId\\\":88}\",\"payload_encoding\":\"string\"}'
      ```
      **期望**：日志出现「重复消息，已忽略」，**库存不再减 1**，订单数不变。

- [ ] **12. 消费失败进死信**
      临时把 `handle()` 里的 `courseStockMapper.tryDecrement` 改成抛 `RuntimeException`，
      重启后抢一次课 → 消息进 `seckill.order.dlq`（管理台能看到 1 条），
      日志有「消费失败，进死信」。**改回来。**

- [ ] **13. 事务回滚能还原**
      上一步的场景下，检查 `course_stock` **没有**变化、`mq_consume_log` **没有**残留记录
      （证明 INSERT log 和扣库存同事务，一起回滚了）

- [ ] **14. 残留 PENDING 为 0**
      跑完一轮 300 并发后：
      ```sql
      SELECT COUNT(*) FROM seckill.seckill_order WHERE status = 0;
      ```
      必须是 **0**。不是 0 就说明有消息丢了——去查死信队列和日志。

---

## §14 验收标准

全部满足才算 v3 完成：

- [ ] `clean compile` BUILD SUCCESS
- [ ] §13 的 14 项全部有证据
- [ ] 300 VU 压测连跑 3 轮，每轮 `success_orders = 100`、`stuck_pending = 0`、死信 0 条
- [ ] P95 与预测表对比，偏差原因写在报告里（**偏差是必答题，不是可选项**）
- [ ] `docs/压测报告-v3.md` 写完，含 §12 的预测/实测对照表
- [ ] `docs/项目开发文档.md` 的阶段 5 与本文差异写进附录
- [ ] git 提交 + annotated tag `v3-mq-async`
- [ ] 安全扫描：`git grep -F -f` 扫一遍密码，零命中

---

## §15 排查顺序

出问题时按这个顺序查，从外到内：

1. **消息发出去了吗？** 管理台 Queues → `seckill.order.queue` 的 `messages`/`deliver` 速率。
   如果 request 有 200 但队列毫无动静 → 生产者没发（日志找 `等待 publisher confirm`）。
2. **消费者收到了吗？** 日志找 `收到消息 msgId=`。
   队列有堆积但日志没打印 → 消费者没启动（`@RabbitListener` 的 Bean 没被扫到？）
   或 `acknowledge-mode` 配错导致容器起不来。
3. **处理成功了吗？** 查 `mq_consume_log.status`：1 成功 / 2 业务失败 / 缺记录 = 消息没到。
4. **订单状态改了吗？** 查 `seckill_order.status`：还是 0 → 第 3 步的 UPDATE 没执行或没提交。
5. **死信里有吗？** `seckill.order.dlq` 的 `messages` 不为 0 → 看日志里的异常栈。
6. **库存对得上吗？** 跑 `loadtest/reconcile.ps1` 和 §11.4 的 SQL。
7. **最后才怀疑 RabbitMQ 本身**：`docker logs seckill-rabbitmq --tail 100`。
   broker 出问题通常伴随连接日志异常，很少静默。

**最快的第一步**：`docker exec seckill-rabbitmq rabbitmqctl list_queues name messages consumers`
一条命令就能看到"有没有消息、有没有人在消费"。`consumers=0` 说明消费者没连上。

---

## §16 必踩的坑（13 条）

1. **`Jackson2JsonMessageConverter` 用不了**
   本项目是 Jackson 3。必须用 `JacksonJsonMessageConverter`（§1.5）。
   用错类的症状：能编译、能启动，发消息时报
   `No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper'`。

2. **队列参数不一致导致启动失败**
   ```
   PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange' ...
   ```
   原因：队列已存在且参数不同。删队列重来（§6.3）。

3. **`default-requeue-rejected` 忘了改 false**
   症状：一条毒消息被无限重投，日志每秒刷几十行，CPU 打满。
   改之前它就是默认 `true`。

4. **ack 在事务提交之前**
   症状：压测时偶尔出现"订单少了"，但日志没有任何异常。
   原因：`basicAck` 之后事务才提交，提交失败时消息已丢。

5. **`@Transactional` 加在 `private` 方法上**
   症状：注解写了但完全不生效，事务根本没开。
   原因：Spring AOP 只拦代理对象上的**外部调用**，同类内部 `this.handle()` 不走代理。
   本项目用 `TransactionTemplate` 手动控制，绕开这个问题。

6. **改了队列声明但忘了删旧队列**
   见第 2 条。开发期遇到 PRECONDITION_FAILED，第一反应就应该是"删队列"。

7. **`basicNack(requeue=true)` 造成死循环**
   见 §4.2。本项目一律 `requeue=false`。

8. **消费者没写 `@RabbitListener`，或写了但 Bean 没被扫到**
   症状：消息在队列里堆着，`consumers=0`，没有任何报错。
   本项目 `com.example.seckill` 是根包，放在 `infra/mq/` 下能被扫到。

9. **以为 broker 内存到水位会"卡住"来提醒你**
   很多资料（包括本任务书的初稿）都说"到水位时 `basicPublish` 阻塞不报错"。
   **在本机这个容器里不会这样**：`vm_memory_high_watermark` 算的是 WSL 虚拟机的 3.83 GB，
   水位 1.53 GB 高于容器的 512 MB 上限（§1.3.1 有实测），所以保护永不先触发，
   更可能是**被内核 OOM 直接杀掉**。
   症状：`docker ps` 里容器没了，或 `docker inspect` 显示 `OOMKilled=true`。
   排查第一条永远是：`docker inspect seckill-rabbitmq --format '{{.State.OOMKilled}} {{.RestartCount}}'`。
   本项目消息量（百条级、每条 114 字节）离这个边界很远，正常情况下不会遇到。

10. **`publisher-returns` 开了但没设 `mandatory`**
    开了 `publisher-returns: true` 只表示"退回的消息会回调到我这里"。
    但 `convertAndSend` 默认不设 `mandatory=true`，所以**路由不到队列时消息被静默丢弃**。
    本项目的 exchange 和 routing key 都是常量，不容易写错，所以本阶段不处理这个；
    但要知道这个洞存在（生产环境应该设 `mandatory=true` + 实现 `ReturnsCallback`）。

11. **消息体用了 `@Data` 类，反序列化时 NoSuchMethod**
    Jackson 反序列化需要无参构造器。用 `record` 或加 `@NoArgsConstructor`。
    本项目用 `record`，天然没这个问题。

12. **`msgId` 用 `orderNo` 代替**
    暂时能跑，但以后一个订单发多条消息就会撞唯一索引（§5.3）。

13. **`stuck_pending` 不为 0 却没发现**
    最危险的一种。库存守恒、没有超卖、没有 500，
    但成功单数只有 87——少了 13 单，用户投诉"我明明点了"。
    **必须把 `SELECT COUNT(*) FROM seckill_order WHERE status = 0` 作为每轮必查项。**

---

## §17 明确不做

写下来是为了防止"顺便加个功能"（P4）。

- ❌ **不做分布式事务**（不引 Seata、不搞 TCC、不做两阶段提交）。
  本阶段用"PENDING 订单 + publisher confirm + 失败补偿"。
  代价：极端情况下可能有 PENDING 残留，靠 §2.1 的 SQL 找出来人工处理。
  **这是刻意的取舍，不是遗漏。**

- ❌ **不做消息重试队列**（不搞 delay exchange、不做指数退避重投）。
  失败直接进死信，人工看。理由：本阶段的失败都是"代码 bug"，重试没用。

- ❌ **不做 PENDING 自动清扫任务**（不加 `@Scheduled`）。
  清扫任务需要考虑"消息还在路上"和"真的丢了"的区别，是个独立课题。

- ❌ **不做退课异步**（§7.4）。

- ❌ **不做订单查询走 Redis**（轮询直接打 MySQL，100 QPS 的主键查询毫无压力）。

- ❌ **不做前端**（阶段 7 才做）。

- ❌ **不改 v0~v2 的实现**。

- ❌ **不加新的 Maven 依赖**（starter-amqp 已经在了）。

- ❌ **不调 RabbitMQ 的内存/CPU 参数**。
  容器 512MB / 0.5 核是既定约束，如实记录在报告里，不偷偷改大。
  **改了就不是"在受限环境下做的压测"了。**

---

## §18 面试话术

**Q：为什么要用 MQ？直接扣库存不是更简单？**

> 因为简单的东西在 300 并发下就崩了。v0 卖了 300 单只该卖 100 单。
> 加锁能卖对，但 100 个请求排队抢同一行，P95 从 60ms 涨到 255ms。
> MQ 解决的不是"卖对"（v1 已经卖对了），而是"用户不用等那 200ms 排队"。
> 快不在算得快，在不等。

**Q：MQ 会丢消息吗？**

> 会，三个位置。生产者到 broker 会丢，靠 publisher confirm 发现；
> broker 内存里的消息重启会丢，靠队列 durable + 消息 persistent；
> 消费者处理到一半崩会丢，靠手动 ack——处理完才 ack，而且要在事务提交之后。
> 我压测的时候专门测了 ack 的位置：先 ack 再处理，看起来吞吐更高，
> 但一旦处理失败就是永久丢单。

**Q：消息重复了怎么办？**

> AMQP 是至少一次投递，重复无法避免，只能让它无害。
> 我用 `mq_consume_log` 表的 `msg_id` 唯一索引做幂等闸，
> INSERT 撞索引就说明处理过，直接 ack 掉。
> 关键点是这个 INSERT 必须和扣库存、改订单在同一个事务里——
> 如果分开了，业务失败回滚后这条消息就永远没法重试了，
> 因为下次来会被幂等闸挡掉。

**Q：消费失败了怎么办？**

> 分三类。可重试的（连接池耗尽）重试；
> 不可重试的（JSON 错、课程不存在）立刻进死信；
> 业务失败（库存真的没了）标记订单失败然后正常 ack——重投一百次库存还是 0。
> 我一律用 `basicNack(requeue=false)`，因为 `requeue=true` 遇到毒消息会无限重投，
> 日志刷爆、CPU 打满，比丢失消息更难排查。

**Q：异步之后怎么保证数据对？**

> 不能保证"永远对"，只能保证"错得可发现、可修复"。
> 三个观测点：`seckill_order.status=0` 的残留数是 0；
> `stock + sold = capacity` 守恒；死信队列是 0 条。
> 任何一个不为 0 就说明有问题。
> 另外我压测时看的是双向判据——不光防"多卖"（超卖），还防"少卖"。
> 少卖更阴险，因为库存账目看起来完全正常，只是成功单数少了。

**Q：为什么不直接上 RocketMQ / Kafka？**

> 本项目要的是"能看见消息在哪"。
> RabbitMQ 有管理台，能直接看到队列堆积、消费速率、死信条数。
> 学习阶段"可观测"比"高吞吐"重要，因为出问题时你得能看见。
> 另外这是单机 512MB 内存的容器，从吞吐上看 5 万 msg/s 也绰绰有余。

---

## 附录 A：与《项目开发文档》的差异

本文档以实测为准。与 §5.4（v3-mq-async 章节）的已知差异：

| 文档原写法 | 实际情况 | 本文档的处理 |
|---|---|---|
| "消息体包含 courseName" | 消息体越大 broker 内存涨得越快（§1.3 实测每条 196 字节） | 只放 4 个字段，课程名消费者自己查 |
| "消费者用 `@Transactional`" | 内部调用不生效 | 用 `TransactionTemplate` 手动控制（§8.5） |
| "失败重试 3 次" | 本阶段失败都是代码 bug，重试没用 | 直接进死信（§17） |
| "用 orderNo 做幂等键" | 一个订单可能发多条消息 | 改用独立 `msgId`（§5.3） |
| "超卖率作为核心指标" | 异步版还可能"少卖" | 增加 `stuck_pending` 与"成功单数恰好等于 capacity"双向判据（§11.4） |
| 未提及 | `mq_consume_log` 没有 `update_time` 列 | 状态更新不记时间，如实记录 |

---

*本文档所有环境数据（吞吐、水位、内存、版本号）均为 2026-11-14 本机实测。*
*代码骨架是参考实现，标"业务"的两个文件请自己写——那才是这一版真正要学的部分。*
