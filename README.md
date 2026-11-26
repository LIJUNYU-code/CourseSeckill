# CourseSeckill — 高并发选课秒杀引擎

一个用**同一份业务**跑出 5 个版本、每版都有实测数字的并发练手项目。

核心问题只有一个：**100 个名额、300 个学生同时点"抢课"，怎么保证不超卖、以及每加一层优化到底值多少毫秒。**

答案不是"加了 Redis 就快了"，而是一张能对着讲的表：

| 版本 | 做法 | P95（300 VU 抢 100 名额） | 成功单数 | 超卖 |
|---|---|---|---|---|
| `v0-baseline` | 查库存 → Java 里减 1 → 写回 | 572 ms | **300** | **+200** |
| `v1-db-lock` | 条件更新 `WHERE stock > 0` | 339 ms | 100 | 0 |
| `v2-redis-lua` | Redis + Lua 原子扣减当门禁 | 322 ms | 100 | 0 |
| `v3-mq-async` | 加 RabbitMQ 异步落单 | **66 ms** | 100 | 0 |

> v0 那行是**故意的**。没有这个"犯罪现场"，后面所有"零超卖"都无从对比。

---

## 快速开始

### 方式一：Docker Compose（一条命令，含 MySQL/Redis/RabbitMQ）

```bash
# 1) 先准备密码。compose 里的密码是强依赖，不设置会直接报错退出（故意这样设计）
cp .env.example .env
#    然后把 .env 里的三个值换成你自己的

# 2) 起服务
docker compose up -d
```

浏览器打开 <http://localhost:8080>，用下面的演示账号登录。

首次启动 MySQL 要初始化建表和造数据，约 30~60 秒；`docker compose logs -f app` 看到
`Started SeckillApplication` 即就绪。

> 宿主机端口是 **3307 / 6380 / 5672 / 15672 / 8080**，避开本机常被占用的 3306 和 6379。
> 要改就看 `docker-compose.yml` 里的 ports，或按 `.env.example` 末尾注释放开对应变量。

### 方式二：本地跑（需要本机已有 MySQL / Redis / RabbitMQ）

```bash
# 1) 建库建表 + 造数据
mysql -u root -p -e "source sql/V1__init.sql"
mysql -u root -p -e "source sql/V2__seed_data.sql"

# 2) 配置密码（这个文件被 .gitignore 忽略，不会进版本库）
cp src/main/resources/application-local.example.yaml src/main/resources/application-local.yaml
#    然后把里面的 password 改成你自己的

# 3) 启动
./mvnw spring-boot:run
```

Windows 下如果 Maven 依赖走的是自定义镜像，需要显式指定 settings：

```powershell
.\mvnw.cmd -s "D:\develop\apache-maven-3.9.15\conf\settings.xml" spring-boot:run
```

### 演示账号

| 角色 | 账号 | 密码 | 说明 |
|---|---|---|---|
| 学生 | `S000000001` ~ `S010000000` | `Abc12345` | 共 1 万个，任选一个 |
| 管理员 | `admin` | `Demo@2026` | 预热库存、对账 |

> 这些是**公开的演示凭据**，真实环境请改掉。

### 切换版本

不用切分支，改一行配置重启即可：

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--seckill.strategy=v2-redis-lua
```

可选值：`v0-baseline` / `v1-optimistic` / `v1-pessimistic` / `v1-version-cas` /
`v2-redis-lua` / `v3-mq-async`。

---

## 页面

一个单页应用（原生 JS，无构建步骤），在 <http://localhost:8080>：

- 学生登录 → 选课程 → 点「立即抢课」→ 剩余名额实时变化
- 「我的选课」列表：待处理 / 已成功 / 已失败 / 已退课 四种状态
- 可退课，退课后名额实时回补
- v3 策略下能看到「待处理 → 已成功」的状态变化（异步落单的真实体感）

> v3 的接口语义是 **HTTP 200 ≠ 已选上**。返回 200 只代表"已受理入队"，
> 订单状态是 `0 待处理`，1 秒内由消费者改成 `1 已成功`。页面会自动轮询。

---

## 接口

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/api/auth/login` | 登录，返回 JWT | 公开 |
| GET | `/api/course/{courseId}` | 课程详情 + 库存 | 登录 |
| POST | `/api/seckill/{courseId}` | 抢课 | 学生 |
| POST | `/api/seckill/{courseId}/cancel` | 退课 | 学生 |
| GET | `/api/order/my?page&size` | 我的选课（分页） | 学生 |
| GET | `/api/order/{orderNo}` | 单条选课详情 | 学生 |
| POST | `/api/admin/cache/stock/{courseId}` | 预热单课库存 | 管理员 |
| POST | `/api/admin/cache/stock` | 预热全部 | 管理员 |
| GET | `/api/admin/cache/stock/{courseId}` | Redis / MySQL 对账 | 管理员 |

统一响应：

```json
{ "code": 200, "message": "操作成功", "data": { } }
```

`code` 与 HTTP 状态码语义一致（400/401/403/404/409/500）。

**在线接口文档**：<http://localhost:8080/swagger-ui/index.html>
先调 `/api/auth/login` 拿 token，点右上角 Authorize 粘贴（不要加 `Bearer ` 前缀）。

---

## 四个版本到底差在哪

### v0 — 会超卖的那一版

```sql
SELECT * FROM course_stock WHERE course_id = ?      -- 线程 A、B 都读到 100
UPDATE course_stock SET stock = 99 WHERE course_id = ?  -- 两个线程都写 99
```

丢失更新。**300 个请求卖出了 300 单，库存只从 100 掉到 96。**

一个反直觉的实测结论：`CHECK (stock >= 0)` 约束**完全没防住超卖**——因为库存根本没掉到负数，
它只保证"库存不为负"，不保证"订单数 ≤ 名额"。

### v1 — affected rows 是唯一可信的返回值

```sql
UPDATE course_stock SET stock = stock - 1, sold = sold + 1
WHERE course_id = ? AND stock > 0
```

判断和扣减压进同一条 SQL，数据库保证原子性。返回 0 就是没抢到。

这一版还实现了悲观锁（`SELECT ... FOR UPDATE`）和版本号 CAS 作对比。
**CAS 版本踩了个大坑**：REPEATABLE-READ 下事务快照冻结，重试循环里每次 `SELECT`
读到的 `version` 永远是旧值，3 次重试用同一个过期答案交卷——300 并发只成功 18 单。
修法是把重试移到事务外（外层循环无事务 + `TransactionTemplate` 每次新事务）。

### v2 — Redis 当门禁

Lua 脚本原子扣减，**200 个注定失败的请求在 Redis 就被挡回，一次数据库都没碰。**

证据不是 P95 变快（那只说明"平均值"），而是 MySQL 自己的计数器：

| 版本 | `Com_update` 增量 | 成功单 |
|---|---|---|
| v2 | **100** | 100 |
| v1 | **300** | 100 |

比例精确 100:300。**数据库自己记的账不会骗人。**

### v3 — 把排队挪出请求路径

v2 之后瓶颈转移了：门禁挡住 200 个，剩下 100 个成功请求仍要在同一行 MySQL 锁上串行排队。

v3 把 `UPDATE course_stock WHERE course_id = 88` 从请求线程移到 MQ 消费者线程。
「削峰」的真实含义不是让事情变快，是**让用户不等**。

代价是接口语义变了（返回 200 = 已受理，不是已选上），并且要额外解决三件事：

1. **消息会丢** → publisher confirm + durable/persistent + 手动 ack
2. **消息会重复** → `mq_consume_log` 的 `uk_msg_id` 做幂等闸，与业务同事务
3. **消费会失败** → 业务失败标 `status=2` 并正常 ack；技术异常 `basicNack(requeue=false)` 进死信

实测最终一致延迟 **≤ 1 秒**（23 单 0 秒、77 单 1 秒）。

---

## 分库分表：做了，回退了

`v5-sharding` 用了 ShardingSphere-JDBC 5.5.3，按 `student_id % 4` 分 4 片。
功能全部验证通过（22 项自测全绿，分片键改造后每单 `Com_update` 4.17 次而非广播的 7 次）。

但性能是这样：

| 版本 | 1200 RPS | 1600 RPS |
|---|---|---|
| v3（不分表） | P95 20.16 ms | **P95 120.8 ms** |
| v5（分表） | P95 87.91 ms | **P95 2.78 s，丢 5412 个请求** |

1200 RPS 慢 4.4 倍，1600 RPS 直接崩。CPU 峰值只 20.8%、FullGC 0 次、连接池不是原因。

**结论：分表解决的是"数据量大"，而这个项目的核心矛盾是"并发高"，两者不是同一个问题。**
1 万行订单做分表是纯亏。改动存在 `git stash`（`git stash list` 可见），没进主线。

这个负面结论本身是项目的一部分——**知道什么时候不该用某个技术，比会用更重要。**

### 补测：2000 万行时结论会变吗？

会怀疑"1 万行太少看不出收益"是合理的，所以补做了一次 2000 万行实测
（[`docs/A8-分表2000万行实测报告.md`](docs/A8-分表2000万行实测报告.md)）。

造数 20 万学生 × 100 课程 = **2000 万行**，表空间 4 GB，耗时 71 分钟。**结论没变，但理由更硬了**：

| 查询 | 2000 万行单表 | EXPLAIN 扫描行数 |
|---|---|---|
| `WHERE id = ?` | **0.062 ms** | 1 |
| `WHERE student_id = ?` | **1.42 ms** | 100 |
| `WHERE course_id = 50 AND status = 1 LIMIT 20` | **0.25 ms** | 398,820 |
| 同上 `LIMIT 100000, 20` | **270 ms** | 398,820 |
| `COUNT(*)` 同条件 | **79 ms** | 398,820 |

**慢的不是"表有 2000 万行"，是"这门课有 39.8 万人"。** 分表后每片仍有 10 万条要扫，扫描总量不变。

两个反直觉的实测结果：

1. **buffer pool 从 128 MB 加到 2 GB（16 倍），深分页 270→272 ms、COUNT 79→96 ms，毫无改善。**
   证明瓶颈是 CPU 逐行处理，不是磁盘 I/O。加内存救不了这类查询。
2. **找到了真正该做的事**：同一张表上，`LIMIT 100000,20` 要 280 ms，
   而 `WHERE id > ? ORDER BY id LIMIT 20`（游标分页）只要 **0.2 ms，快 1000 倍**。
   这比上分库分表便宜得多。

代价方面，2000 万行规模上重新确认了跨分片唯一索引失效：同一 `order_no` 可同时存在于两个分片
（同片内重复则报 `ERROR 1062`）。**这是正确性缺陷，比慢几十毫秒严重得多。**

---

## 压测

```bash
# 单课极限（300 VU 抢 100 名额）
k6 run loadtest/seckill-single.js

# v3 异步口径（数不入库成功，只数已入队）
k6 run loadtest/seckill-v3.js

# 容量扫描（逐档加压找拐点）
./loadtest/capacity-scan-v4.ps1 -Rates 400,800,1200,1600

# JVM 零安装采样（不需要 Prometheus）
./loadtest/jvm-probe.ps1 -DurationSec 60
```

实测容量拐点（本机 i7-14650HX / 16 GB / MySQL 与 RabbitMQ 同机）：
**1600 RPS 零失败、P95 120.8 ms 是可信上限**；再往上受限于压测机自身的 VU 池。

详细的压测方法、数据与结论见 `docs/`：

- `压测报告.md` — v0 复现超卖
- `压测报告-v1.md` — 三种数据库层并发控制对比
- `压测报告-v2.md` — Redis 门禁的 Com_update 对照实验
- `压测报告-v3.md` — 异步落单与最终一致延迟
- `项目总结报告.md` — 四版合并总结 + 面试话术

---

## 技术栈

| 层 | 选型 |
|---|---|
| 运行时 | Java 26 / Spring Boot 4.1.1 |
| 持久层 | MyBatis-Plus 3.5.17 + MySQL 8.0 |
| 缓存 | Redis（Lua 原子扣减） |
| 消息 | RabbitMQ 3.13（手动 ack + 死信队列） |
| 安全 | Spring Security + JWT（jjwt 0.12.6） |
| 文档 | springdoc-openapi 3.2.0 |
| 压测 | k6 v0.55.0 |

> Spring Boot 4 用 **Jackson 3**（`tools.jackson.databind`），但注解包仍是
> `com.fasterxml.jackson.annotation`。这两个包名混用是本项目踩过的坑之一。

---

## 目录结构

```
seckill-course/
├── src/main/java/com/example/seckill/
│   ├── common/          Result、ResultCode（唯一的错误码来源）
│   ├── config/          Security / MyBatis-Plus / Redis / RabbitMQ / OpenAPI
│   ├── controller/      5 个 REST 入口
│   ├── dto/ vo/         请求响应对象
│   ├── entity/          6 张表的映射
│   ├── exception/       BusinessException + 全局异常处理
│   ├── infra/cache/     StockCacheService（Redis 门禁）
│   ├── infra/mq/        生产者 / 消费者 / 消息体
│   ├── mapper/          MyBatis-Plus Mapper
│   ├── security/        JwtUtil + JwtAuthenticationFilter
│   └── service/impl/    ★ 全部版本策略都在这里
│       ├── SeckillServiceImpl.java              v0
│       ├── SeckillServiceImplV1Optimistic.java  v1-A
│       ├── SeckillServiceImplV1Pessimistic.java v1-B
│       ├── SeckillServiceImplV1VersionCas.java  v1-C
│       ├── SeckillServiceImplV2RedisLua.java    v2
│       └── SeckillServiceImplV3MqAsync.java     v3
├── src/main/resources/
│   ├── static/index.html   单页前端
│   ├── application.yaml
│   └── sharding.yaml       （v5 遗留，主线未启用）
├── sql/                 V1 建表 / V2 造数据
├── loadtest/            k6 脚本与 PowerShell 工具
├── docs/                任务书 + 压测报告
├── Dockerfile
└── docker-compose.yml
```

**版本切换的原理**：6 个实现都 `implements SeckillService`，靠
`@ConditionalOnProperty(name = "seckill.strategy", havingValue = "...")` 决定谁来生效。
不用切分支的理由写在 `docs/项目开发文档.md` §7.3。

---

## 测试

```bash
./mvnw test
```

37 条，覆盖库存扣减、幂等、退课、课程查询四条主链路的关键分支。

其中课程查询那组（14 条）特意覆盖了两个容易漏的地方：一是**管理员与学生的 id 撞号**（`sys_user.id=1` 恰好等于 `S000000001` 的 id），确保角色判断生效、管理员不会看到学生的选课状态；二是 `listCourses` 只查一次订单表，用调用次数断言守住 N+1。

CI 在 `.github/workflows/ci.yml`：每次 push 跑编译 + 全部测试。

---

## 已知局限

1. **压测机与被测机是同一台**。内存 15.6 GB，压测时必须关掉 IDE 和浏览器，否则数据是换页噪声。
2. **RabbitMQ 是单节点容器**（512 MB / 0.5 核）。broker 不会因为水位先阻塞，而是可能被内核 OOM 杀——排查第一步永远是
   `docker inspect seckill-rabbitmq --format '{{.State.OOMKilled}} {{.State.RestartCount}}'`。
3. **confirm 超时后的窗口期**：超时但消息其实到了，可能导致 Redis 多还一次库存。见 `压测报告-v3.md` §9.1。
4. **没有限流**。1600 RPS 以上是硬崩，不是优雅降级。
5. **v3 的成功单数无法从 HTTP 层直接数**，只能压测后查库。
6. **前端是单页 demo**，没有分页组件、没有移动端适配。

---

## License

学习项目，代码可自由参考。文档里的实测数字都来自本机真实压测，可以复现，但**不要直接当成生产环境指标**。
