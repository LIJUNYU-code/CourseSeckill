# 阶段 4 任务书：v2-redis-lua

> 项目：seckill-course 高并发选课引擎
> 阶段：阶段 4（v2-redis-lua）
> 前置：`v1-db-lock`（tag `v1-db-lock`，提交 `250e6d1`）
> 目标版本：`seckill.strategy=v2-redis-lua`

---

## 0. 这一阶段要解决什么

v1 已经把超卖修好了，但报告 §11 列出的三个问题一个没解决：

| v1 的问题 | 数据 | v2 怎么解决 |
|---|---|---|
| 300 个请求全部打到 MySQL 才被拒 | 300 次 UPDATE，200 次注定失败 | Redis 在内存里先挡掉 200 个 |
| CAS 靠重试，退化成重试风暴 | 需重试 30 次，P95 1.1 s | Lua 原子扣减，**一次成功或一次失败，不需要重试** |
| P95 的尾部延迟高 | 339 ~ 1100 ms | Redis 操作微秒级 |

**这一版的靶子是「把无谓的请求挡在数据库之前」，不是「让数据库更快」。**

数据库能力不变，我们只是减少打给它的请求数。

**但这一版会引入 v1 完全没有的一类问题：两个存储之间的不一致。**
v1 只有 MySQL 一个真相来源，怎么测都不会"两边对不上"。
v2 有 Redis 和 MySQL 两份数据，它们可能不同步——这是本阶段真正的难点，
也是任务书花最多篇幅的地方（§9、§10）。

---

## 1. 前置事实（全部已实测）

### 1.1 Redis 环境

| 项 | 值 |
|---|---|
| 实现 | Memurai 8.2.10（Windows 上的 Redis 兼容实现） |
| 端口 | 6379 |
| `maxmemory` | 8589934592（8 GB） |
| `appendonly` | **no** |
| `save` | `3600 1` `300 100` `60 10000`（RDB 快照） |
| CLI | `D:\tools\Redis\memurai-cli.exe` |
| 服务端 | `D:\tools\Redis\memurai.exe` |

### 1.2 🔴 Memurai 会自己退出，每次开工必须先启动

实测多次：Memurai 进程会自行结束。**它没有配成 Windows 服务（`Start-Service Memurai` 报
"无法打开服务：拒绝访问"，权限不足）**，只能手动起进程：

```powershell
Start-Process -FilePath "D:\tools\Redis\memurai.exe" `
  -ArgumentList "`"D:\tools\Redis\memurai.conf`"" -WindowStyle Hidden

# 验证（不要只看进程在不在，要看它答不答应）
& "D:\tools\Redis\memurai-cli.exe" ping      # 必须返回 PONG
```

**这直接影响 v2 的测试**：如果压测跑到一半 Redis 挂了，你会看到一批奇怪的 500，
而不是超卖——这种事在 v1 不存在，v2 必须提前想清楚怎么办（见 §9.5）。

### 1.3 Lua 语义实测（已跑通，非推测）

脚本：

```lua
local s = tonumber(redis.call('get', KEYS[1]) or '-1')
if s <= 0 then return -1 end
redis.call('decr', KEYS[1])
return s - 1
```

| 操作 | 结果 |
|---|---|
| `SCRIPT LOAD` | SHA1 `bf4db62e0363b90bbb01412abde2915d8f1c975d` |
| `SET probe:stock 3` 后第 1 次 `EVALSHA` | `2` |
| 第 2 次 | `1` |
| 第 3 次 | `0` |
| 第 4 次 | `-1`（库存耗尽） |
| key 不存在时 | `-1` |

**用假 SHA 调 `EVALSHA` 的报错原文**（你迟早会见到它）：

```
NOSCRIPT No matching script. Please use EVAL.
```

### 1.3.1 §6.1 那份脚本本身也验过了（六个分支全覆盖）

上面那个是探针脚本。**任务书 §6.1 要你写的正式脚本我已经完整跑过一遍**，六个分支：

| 前置 | 输入 | 返回 | 说明 |
|---|---|---|---|
| `del` key（不存在） | amount=1 | **-1** | 未预热 |
| `set` key `0` | amount=1 | **-2** | 库存不足 |
| `set` key `1` | amount=1 | **0** | **正好卖完，不能误判为不足**（这是 `<` 与 `<=` 的分界） |
| `set` key `3`，连扣 4 次 | amount=1 ×4 | **2 → 1 → 0 → -2** | 第 4 次才拒绝 |
| `set` key `3` | amount=2 | **1** | `DECRBY` 参数化正确 |
| `set` key `-5` | amount=1 | **-2** | 脏数据（负数）也被挡住 |

**所以 §6.1 的代码可以直接抄，不用改。**

> 🔴 **但用命令行验它时有个 PowerShell 坑**：`memurai-cli --eval <脚本> <key> , 1`
> 里的那个**逗号是分隔符，必须加引号**写成 `','`。不加的话 PowerShell 会把
> `<key> , 1` 当成数组展开，结果 key 变成一个空字符串，脚本里 `GET` 到 nil
> 之外的东西，报一个和脚本毫无关系的错：
>
> ```
> ERR user_script:8: attempt to compare number with nil
> ```
>
> 我第一次跑就是被这个骗了 —— **看起来像脚本 bug，实际是命令行传参问题**。
> 这个错误的教训比脚本本身值钱：**报错信息指向的那一行，不一定就是出问题的地方。**
> 用 Java 调（`StringRedisTemplate.execute`）不存在这个问题，它按参数顺序传。

### 1.4 🔴 `commons-pool2` 不在 classpath —— 现有连接池配置是死配置

`application.yaml` 第 35-40 行写了：

```yaml
      lettuce:
        pool:
          max-active: 50
          max-idle: 20
          min-idle: 5
```

实测 `dependency:tree` **没有 `commons-pool2`**。而 Lettuce 的连接池是**可选**的：
没有 commons-pool2 时，这些 `pool.*` 配置**被完全静默忽略**，Lettuce 退回**单连接共享**
模式（一个连接多路复用，本身不弱，但不限制并发数）。

**这是我们自己写的注释在骗自己**——第 37 行写着"连接数不够会直接体现为 P99 抖动"，
而实际上连接池根本没启用。

两种处理，二选一（**我推荐 B**）：

| 方案 | 做法 | 后果 |
|---|---|---|
| A | pom 加 `commons-pool2` 依赖 | 连接池真的生效，但多一个依赖、多一份调参负担 |
| **B** | 删掉那段 `pool` 配置，改用注释说明"单连接多路复用" | 配置不再骗人；Lettuce 单连接在 300 并发下足够（实测见 §12） |

选 B 的理由：**这一版的目标是验证"Redis 挡请求"这个机制，不是调连接池**。
留一段不生效的配置在文件里，比没有配置更糟——三个月后你会以为它是生效的。

> 本地仓库已有 `commons-pool2-2.13.1.jar`，如果改主意选 A，离线也能装。

### 1.5 现有代码结构（v1 结束时的真实状态）

```
com/example/seckill/
├── common/        Result, ResultCode
├── config/        MybatisPlusConfig, OpenApiConfig, PasswordConfig, SecurityConfig
├── controller/    AuthController, CourseController, OrderController, SeckillController
├── dto/           CourseDetailResponse, LoginRequestDTO, LoginResponseDTO,
│                  MyOrderPageResponse, OrderResponse, SeckillResponse
├── entity/        Course, CourseStock, SeckillOrder, Student, SysUser
├── exception/     BusinessException, GlobalExceptionHandler
├── mapper/        CourseMapper, CourseStockMapper, SeckillOrderMapper,
│                  StudentMapper, SysUserMapper
├── security/      JwtAuthenticationFilter, JwtUtil
└── service/       AuthService, CourseService, OrderService, SeckillService
    └── impl/      AuthServiceImpl, CourseServiceImpl, OrderServiceImpl,
                   SeckillServiceImpl(v0), SeckillServiceImplV1Optimistic,
                   SeckillServiceImplV1Pessimistic, SeckillServiceImplV1VersionCas
```

**没有 `infra` 包**，本阶段要新建。

### 1.6 `CourseStockMapper` 现有方法（v2 会用到）

```java
CourseStock selectByCourseId(@Param("courseId") Long courseId);   // 普通查询
CourseStock selectForUpdate(@Param("courseId") Long courseId);    // 行锁
int tryDecrement(@Param("courseId") Long courseId);               // 条件更新，v2 复用这个
int absoluteUpdate(@Param("courseId") Long newStock);             // 绝对值覆盖（v0 用）
int returnStock(@Param("courseId") Long courseId);                // 退课归还
int casDecrement(@Param("courseId") Long courseId, @Param("version") Integer version);
```

`tryDecrement` 的 SQL（**v2 直接复用，不要改**）：

```sql
UPDATE course_stock SET stock = stock - 1, sold = sold + 1
WHERE course_id = #{courseId} AND stock > 0
```

---

## 2. 核心原理：为什么 Lua 是原子的，而"读一次再写一次"不是

这一节必须先讲清楚，否则 §6 的脚本你只是抄。

### 2.1 Redis 执行 Lua 的方式

Redis **单线程**执行命令。当它执行一个 Lua 脚本时，**整段脚本作为一条命令执行，
中间不会插入任何其他客户端的命令**。

```
客户端 A: EVAL "if get(k) > 0 then decr(k) end"   ←── 整段原子
客户端 B: DECR k                                    ←── 只能排在 A 之后
```

对比 Java 里的写法：

```java
// ❌ 错误：两条独立命令，中间任何人都能插进来
String s = redis.opsForValue().get(key);      // 时刻 T1：读到 1
if (Integer.parseInt(s) > 0) {
    redis.opsForValue().decrement(key);        // 时刻 T2：写 0
}
```

T1 和 T2 之间，可能有 99 个线程也读到了 1，然后也去写 0。
**这就是 v0 那个丢失更新的翻版，只是从 MySQL 换到了 Redis。**

### 2.2 一句话的记忆锚点

> **MySQL 的行锁保护一条 SQL；Redis 的单线程保护一次命令（包括整段 Lua）。**

v1 用 `WHERE stock > 0` 把"读-判断-写"压进**一条 SQL**，
v2 用 Lua 把同样三步压进**一次命令**。手段不同，道理完全一样。

### 2.3 为什么不用 Redis 的 `DECR` + 事后检查

有人会想："`DECR` 本身就是原子的，先减再看结果不就行了？"

```java
Long left = redis.opsForValue().decrement(key);   // 减了才知道
if (left < 0) {
    redis.opsForValue().increment(key);            // 还得加回去
    throw new BusinessException("名额已满");
}
```

**能工作，但有三个问题**：

1. **多一次往返**：超卖时 `DECR` + `INCR` 两次网络往返，Lua 一次搞定
2. **中间态可见**：`left` 短暂为负数，如果 key 被别处读取（比如监控、对账脚本），会看到 `-37`
3. **归还失败就永久泄漏**：`INCR` 如果因为连接断了没执行，这个名额永远回不来

Lua 版本在脚本内部判断，**失败时连 `DECR` 都不执行**，没有中间态。

---

## 3. 接口契约（与 v1 完全一致）

**这一版不新增业务接口，不改任何请求/响应格式**——这是"一次只改一个变量"的要求。
外部观察者应该只能看到"变快了"，不能看到"接口变了"。

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/seckill/{courseId}` | POST | 抢课（无 body） |
| `/api/seckill/{courseId}/cancel` | POST | 退课 |
| `/api/order/{orderNo}` | GET | 订单详情（校验归属） |
| `/api/order/my?page&size` | GET | 我的订单 |
| `/api/course/{courseId}` | GET | 课程详情（含 `myOrderStatus`） |

**新增两个运维接口**（不是业务接口，放 `/api/admin/cache/**`，需 `ROLE_ADMIN`）：

| 接口 | 方法 | 用途 |
|---|---|---|
| `/api/admin/cache/stock/{courseId}` | POST | 手动预热单个课程 |
| `/api/admin/cache/stock` | POST | 批量预热全部课程 |
| `/api/admin/cache/stock/{courseId}` | GET | 查看 Redis 里该课程的库存（对账用） |

这三个接口是**为了让不一致可被观测**，没有它们你只能靠猜。
生产环境该关掉，本项目留着，因为压测期间要用。

---

## 4. 文件清单

### 4.1 新建（6 个 Java + 1 个测试）

| 文件 | 作用 |
|---|---|
| `infra/cache/StockLuaScript.java` | 存放 Lua 脚本常量与 SHA（不写逻辑） |
| `infra/cache/StockCacheService.java` | 接口：预热 / 扣减 / 归还 / 查询 / 清除 |
| `infra/cache/impl/StockCacheServiceImpl.java` | 实现，用 `StringRedisTemplate` |
| `config/RedisConfig.java` | `StringRedisTemplate` 的 Bean 与序列化器说明 |
| `controller/CacheAdminController.java` | 三个运维接口 |
| `service/impl/SeckillServiceImplV2RedisLua.java` | 本阶段主体 |
| `src/test/java/.../infra/cache/StockCacheServiceImplTest.java` | 单元测试（Mockito） |

### 4.2 修改（4 个）

| 文件 | 改动 |
|---|---|
| `pom.xml` | **不改**（redis starter 已有）。除非你选 A 方案加 commons-pool2 |
| `application.yaml` | 删掉不生效的 `lettuce.pool`（§1.4），加 `seckill.cache.*` 配置 |
| `loadtest/reset.ps1` | 复位时同时清 Redis key |
| `loadtest/verify.ps1` | 判定时同时输出 Redis 余量与 DB 余量 |

### 4.3 一行都不要改

- `SeckillServiceImpl.java`（v0）
- `SeckillServiceImplV1Optimistic.java` / `V1Pessimistic` / `V1VersionCas`
- `CourseStockMapper.java`（`tryDecrement` 直接复用）
- 所有 v1 的单元测试

**理由**：这四版要能随时切回去做对比（§11.4 要求）。改了旧版，历史数据就不可信了。

---

## 5. 库存预热

### 5.1 key 命名

```
seckill:stock:{courseId}
```

写成 `seckill:stock:88`，值是**字符串形式的剩余库存**，如 `"100"`。

前置 `seckill:` 是给别人留空间（订单缓存、限流计数将来都要前缀）。
不要用 `{88}` 这种 Hash Tag 语法——那是 Redis Cluster 的分片标记，本项目单机用不上，
写了反而让人以为你在考虑集群。

### 5.2 三种预热时机

| 时机 | 触发 | 用途 |
|---|---|---|
| **启动时** | `ApplicationRunner` 启动后加载全部课程 | 保证服务一启动就是可用状态 |
| **按需** | Lua 返回"未预热"时现场加载 | 兜住"服务起来了但 Redis 刚重启"的情况 |
| **手动** | `POST /api/admin/cache/stock` | 压测前复位、数据修正 |

三种都要实现。只做启动预热的系统，Redis 一重启就全站不可用。

### 5.3 🔴 预热的值来自 `stock`，不是 `capacity`

这是本阶段最容易写错的一行：

```java
// ❌ 错：把容量当库存
redis.opsForValue().set(key, String.valueOf(stock.getCapacity()));

// ✅ 对：把当前剩余当库存
redis.opsForValue().set(key, String.valueOf(stock.getStock()));
```

**为什么**：压测跑到一半（比如已经卖了 60 个），服务重启 →
用 `capacity` 预热会把库存重置回 100，**凭空多出 60 个名额**。
用 `stock` 预热，恢复的是 40，与数据库一致。

种子数据里 `stock == capacity == 100`，两者相等，**这个 bug 在第一个用例里看不出来**。
必须专门测：卖 60 个 → 重启 → 检查 Redis 是不是 40（§13 第 6 项）。

### 5.4 预热的幂等性

```java
public void preheat(Long courseId) {
    CourseStock stock = courseStockMapper.selectByCourseId(courseId);
    if (stock == null) return;
    // 必须 SET，不能用 SETNX
    // SETNX 会在"key 已存在但值过期"时静默失败，反而更难查
    stringRedisTemplate.opsForValue().set(stockKey(courseId), String.valueOf(stock.getStock()));
}
```

**用 `SET` 不用 `SETNX`**：预热是"强制对齐数据库"，重复执行必须幂等且以数据库为准。
`SETNX` 的语义是"只在不存在时设"，会把第二次预热变成无操作——而第二次预热可能正是
你为了修数据故意做的。

---

## 6. Lua 脚本

### 6.1 完整脚本

放在 `infra/cache/StockLuaScript.java` 里作为常量：

```java
package com.example.seckill.infra.cache;

/**
 * 库存扣减的 Lua 脚本。
 * <p>
 * 放在 Java 常量里而不是 resources 目录，是因为脚本极短（7 行），
 * 拆成两个文件会让"改脚本"和"改调用"分散到两处，容易只改一半。
 */
public final class StockLuaScript {

    private StockLuaScript() {
    }

    /**
     * 原子扣减库存。
     * <p>
     * KEYS[1] = 库存 key
     * ARGV[1] = 扣减数量（本项目固定为 1）
     * <p>
     * 返回值：
     *   -1 = key 不存在（未预热）
     *   -2 = 库存不足
     *  >=0 = 成功，值为扣减后的剩余量
     */
    public static final String DECREMENT = """
            local key = KEYS[1]
            local amount = tonumber(ARGV[1])
            local current = redis.call('GET', key)
            if not current then
                return -1
            end
            local stock = tonumber(current)
            if stock < amount then
                return -2
            end
            redis.call('DECRBY', key, amount)
            return stock - amount
            """;
}
```

### 6.2 逐个细节解释

| 写法 | 为什么必须这样 |
|---|---|
| `redis.call('GET', key)` 而不是 `redis.GET(key)` | `redis.GET` 是某些客户端的写法，**Redis 内置 Lua 里没有**，写了报 `attempt to index global 'redis'` 之类 |
| `if not current then return -1` | key 不存在时 `GET` 返回 **Lua 的 `false`**（不是 nil、不是空串），`tonsumber(false)` 会报错。必须先判不存在 |
| `tonumber(current)` | 从 Redis 拿到的值字符串，Lua 里比较字符串 `"9" < 10` 会报错（尝试比较 string 与 number） |
| `if stock < amount` | 用 `<` 而不是 `<=`：`stock=1, amount=1` 时应该成功，`1 <= 1` 会误判为不足 |
| `redis.call('DECRBY', key, amount)` | 用 `DECRBY` 而不是 `DECR`，虽然本项目 amount 恒为 1，但参数化后不用改脚本 |
| `return stock - amount` | **返回值是数字不是字符串**，Spring 会转成 `Long`。返回 `tostring(...)` 会得到 `String`，类型对不上 |

### 6.3 ❌ 五种会失败/会出错的写法

**错误 1：判断和扣减分成两次 redis.call，但仍然不是原子的**

```lua
-- ❌ 错
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock > 0 then
    redis.call('DECR', KEYS[1])
    return 1
end
return 0
```

技术上**这段是原子的**（整段 Lua 一起执行），但它丢了两个重要信息：
返回 `1` 而不是剩余量，调用方无法知道库存还剩多少，也没法区分"未预热"。
**能跑，但不好用。** 关键是：**判断和扣减必须在同一段脚本内**，这点它做到了。

**错误 2：脚本只做 DECR，判断在外面**

```java
// ❌ 这是 v0 的翻版
Long left = stringRedisTemplate.opsForValue().decrement(key);
if (left < 0) { ... }
```

见 §2.3。

**错误 3：把 key 拼进脚本字符串**

```java
// ❌ 绝对不能这样
String script = "return redis.call('get', 'seckill:stock:" + courseId + "')";
```

- 有**注入风险**（courseId 若来自外部且未校验）
- **Redis Cluster 下必错**：集群靠 `KEYS` 数组算分片，拼进脚本它算不出来
- 无法利用 `EVALSHA` 的命令缓存（每次脚本文本不同 = 每次都要重新编译）

**必须走 `KEYS` / `ARGV` 参数**。

**错误 4：`tonumber` 前没判 nil**

```lua
-- ❌ key 不存在时报错
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock < 1 then return -1 end
```

`redis.call('GET', 不存在的key)` 返回 **`false`**，`tonumber(false)` 抛
`attempt to compare...` 或 `bad argument`。**必须先 `if not current then`**。

**错误 5：返回字符串**

```lua
return tostring(stock - amount)   -- ❌ 得到 String，不是 Long
```

Spring 的 `RedisScript<Long>` 声明了返回类型 Long，脚本返回字符串会转换失败，
或者你能拿到但 `instanceof Long` 判断失败。

### 6.4 `EVAL` 还是 `EVALSHA`

| 方式 | 首次开销 | 后续开销 | 风险 |
|---|---|---|---|
| `EVAL` | 编译脚本 | **每次都重新编译**（脚本文本要走网络） | 无 |
| `EVALSHA` | 无（需先 SCRIPT LOAD） | 只传 40 字节 SHA1 | **可能 `NOSCRIPT`** |

**本项目的做法：用 Spring 的 `DefaultRedisScript`，它内部自动处理**——
首次 `EVAL`，之后 `EVALSHA`，遇到 `NOSCRIPT` 自动回退 `EVAL`。

```java
@Bean
public DefaultRedisScript<Long> stockDecrementScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptText(StockLuaScript.DECREMENT);
    script.setResultType(Long.class);     // ← 不设这个，返回值是 Object，要自己强转
    return script;
}
```

**坑**：如果你自己 `SCRIPT LOAD` 然后手写 `EVALSHA`，Redis 重启后脚本缓存清空，
会连续报 `NOSCRIPT No matching script. Please use EVAL.`（§1.3 实测过这个报错）。
用 `DefaultRedisScript` 就不用管。

---

## 7. Service 实现

### 7.1 整体流程

```
请求进来
  │
  ├─ 1. 查课程（校验存在、时间窗口）           ← MySQL 1 次
  │
  ├─ 2. Lua 扣减 Redis 库存                    ← Redis 1 次
  │      ├─ -1 未预热 → 预热后重试一次
  │      ├─ -2 库存不足 → 409「该课程名额已满」  ★ 200 个请求在这里返回
  │      └─ >=0 成功 → 继续
  │
  ├─ 3. MySQL tryDecrement（条件更新）          ← 只有 100 个请求走到这里
  │      └─ affected == 0 → 归还 Redis + 抛 409
  │
  ├─ 4. 查重（同一学生是否已选过）              ← MySQL 1 次
  │      └─ 已选过 → 回滚（归还 Redis + MySQL）
  │
  └─ 5. INSERT 订单                            ← MySQL 1 次
```

**关键点：第 1 步查课程也要在 Redis 之前吗？**

是的，但它是**只读**操作，且课程不存在/时间窗口外是极少数，
可以用 `@Cacheable` 优化（**本阶段不做**，见 §17）。

### 7.2 为什么第 3 步还要扣 MySQL

有人会问：Redis 已经扣了，为什么数据库还要扣？两个存储都维护库存不是自找麻烦吗？

**因为 MySQL 必须是真相来源。** 三个理由：

1. **Redis 可能丢数据**：`appendonly=no`（§1.1），宕机重启会丢最近的写入。
   如果只有 Redis 有库存，重启后库存就错了，且**无法恢复**
2. **对账需要基准**：`stock + sold = capacity` 这个判据只有在 MySQL 里才成立
3. **可以对账恢复**：Redis 挂了，从 MySQL 的 `stock` 重新预热即可自愈

**代价**：MySQL 仍然要处理 100 次 UPDATE。但 300 → 100 是 3 倍削减，
这就是本阶段要拿到的收益。

### 7.3 失败时必须归还 Redis

| 失败点 | 需要归还 Redis？ | 需要回滚 MySQL？ |
|---|---|---|
| Lua 返回 `-2`（库存不足） | 否（没扣） | 否 |
| MySQL `tryDecrement` affected=0 | **是** | 否（没改） |
| 查到重复订单 | **是** | **是**（`tryDecrement` 已扣） |
| `INSERT` 撞 `uk_student_course` | **是** | **是** |
| 其他异常 | **是** | **是** |

`tryDecrement` 之后的任何失败都必须归还 Redis，否则**那个名额永久消失**。

实现上的选择：用 `@Transactional` 保证 MySQL 回滚，用 `try/finally` 保证 Redis 归还。

```java
boolean redisDecremented = false;
try {
    Long left = stockCacheService.tryDecrement(courseId);
    // ... 处理 -1 / -2
    redisDecremented = true;

    // 以下在事务里
    return transactionTemplate.execute(status -> {
        int affected = courseStockMapper.tryDecrement(courseId);
        if (affected == 0) throw new BusinessException(...);
        // 查重、插入 ...
    });
} finally {
    if (redisDecremented && 业务失败) {
        stockCacheService.increment(courseId);   // 归还
    }
}
```

**注意 `finally` 里归还的条件**：只有"Redis 扣了但业务没成功"才归还。
成功时归还等于把卖出去的名额又放回去——这比不归还更糟。

### 7.4 归还本身也可能失败

`INCR` 抛异常（Redis 挂了）怎么办？

**本阶段的答案：记录 error 日志，不重试，交给对账（§10）。**

```java
} finally {
    if (needRollback) {
        try {
            stockCacheService.increment(courseId);
        } catch (Exception e) {
            // 吞掉，但必须留证据
            log.error("Redis 库存归还失败，需要对账 courseId={} studentId={}", courseId, studentId, e);
        }
    }
}
```

**为什么不在 finally 里重试**：
- `finally` 里的异常会**覆盖原始异常**，导致你看到的是"归还失败"而不是真正的病因
- Redis 挂的时候，重试也失败，只会拖长响应时间
- **有没有归还成功，对账能查出来**（§10），不需要在请求路径上死磕

这条日志是**对账的入口**：压测后 grep 它，就知道有没有泄漏。

---

## 8. 序列化器的坑

### 8.1 🔴 用 `RedisTemplate` 会导致 Lua 读不到 key

`RedisTemplate` 默认用 `JdkSerializationRedisSerializer`，key 会被序列化成二进制：

```
Java 里写的 key:  "seckill:stock:88"
Redis 里实际是:   "\xac\xed\x00\x05t\x00\x11seckill:stock:88"
```

后果：

- `memurai-cli.exe get seckill:stock:88` → **返回 nil**（你以为没预热）
- `memurai-cli.exe keys *` → 一堆看不懂的乱码
- Lua 里 `redis.call('GET', KEYS[1])` 拿到的 `KEYS[1]` 若也用同一序列化器倒是能对上，
  **但命令行无法观测**，对账全靠猜

### 8.2 正确做法：用 `StringRedisTemplate`

```java
@Service
@AllArgsConstructor
@Slf4j
public class StockCacheServiceImpl implements StockCacheService {

    private final StringRedisTemplate stringRedisTemplate;   // ← 注意是 String 版
    private final DefaultRedisScript<Long> stockDecrementScript;
```

`StringRedisTemplate` 的 key 和 value 都是 UTF-8 字符串，`memurai-cli` 直接可读。

**Spring Boot 会自动提供 `StringRedisTemplate` 吗？** 会——
`RedisAutoConfiguration` 在 classpath 有 spring-data-redis 时同时注册
`RedisTemplate` 和 `StringRedisTemplate` 两个 Bean。**不需要自己写 `config/RedisConfig` 定义它。**

那 `config/RedisConfig.java` 还要不要？

**要，但只放脚本 Bean。**（§6.4 的 `DefaultRedisScript`）
不要在里面重复定义 `StringRedisTemplate`——重复定义会覆盖自动配置，
你在 `application.yaml` 配的 `spring.data.redis.*` 就失效了。

### 8.3 调用 Lua 的写法

```java
List<String> keys = List.of(stockKey(courseId));
Long result = stringRedisTemplate.execute(stockDecrementScript, keys, "1");
```

三个细节：

1. **`execute(script, keys, args...)` 里的 args 是 varargs `Object`**，
   会被 `StringRedisTemplate` 的 value 序列化器转成字符串。传 `"1"` 或 `1` 都行，
   **但传 `1` 时用的是 `Long` 的字符串形式，也是 `"1"`**，等价。
2. **返回值可能是 `null`**：脚本返回 `nil`/`false` 时 Spring 给 `null`。
   我们脚本的每条路径都 `return` 了数字，理论上不会 null，**但仍要判**——
   否则 `result == -1L` 拆箱会 NPE。
3. **脚本报错会抛 `RedisSystemException`**，不是返回错误码。要 try/catch。

```java
Long result;
try {
    result = stringRedisTemplate.execute(stockDecrementScript, keys, "1");
} catch (Exception e) {
    log.error("Lua 执行失败 courseId={}", courseId, e);
    throw new BusinessException(ResultCode.INTERNAL_ERROR, "系统繁忙，请重试");
}
if (result == null) {
    throw new BusinessException(ResultCode.INTERNAL_ERROR, "库存脚本返回空");
}
if (result == -1L) { /* 未预热 */ }
if (result == -2L) { throw new BusinessException(ResultCode.CONFLICT, "该课程名额已满"); }
```

---

## 9. 一致性的五个场景

这一节是本阶段的核心。**五个场景都要实际验证，不是只在代码里想过。**

### 9.1 场景 1：全部成功（正常路径）

```
Lua 扣 Redis 成功 → MySQL tryDecrement 成功 → INSERT 成功 → 返回 200
```

结果：Redis 库存 -1，DB `stock` -1、`sold` +1，订单 +1。两边一致。✅

### 9.2 场景 2：Redis 扣了，MySQL 条件更新失败

```
Lua 扣 Redis 成功 → MySQL tryDecrement affected=0
```

**这理论上看会发生吗？** 不应该——Redis 预热自 DB，Redis 有余量说明 DB 也该有余量。
**但会发生的真实情况**：

- Redis 预热的值比 DB 新（DB 被手工改过，比如 `reset.ps1` 只改了 DB 没清 Redis）
- 有人绕过应用直接改 DB
- 上一轮压测留下的脏数据

处理：**归还 Redis + 抛 409**。
这一条的价值是**防御性编程**——它是"两边对不上"的第一个探测点。

### 9.3 场景 3：Redis 扣了，查到重复订单

```
Lua 扣 Redis 成功 → MySQL tryDecrement 成功 → 查重发现已选过 → 409
```

处理：**归还 Redis + MySQL 事务回滚**。

**这是最常见的"扣了又还"路径**（同一学生重复点击）。
必须有测试（§13 第 3 项），否则每次重试都泄漏一个名额。

### 9.4 场景 4：Redis 扣了，进程崩溃

```
Lua 扣 Redis 成功 → 💥 进程被杀
```

结果：**Redis 少 1，DB 没动，订单不存在**。这个名额从 Redis 视角看消失了。

**这是 v2 无法完美解决的场景。** 同步代码里没有"崩溃后还能执行"的地方,
`finally` 也救不了 `kill -9`。

处理方式有两种，本项目都要：

| 方式 | 做法 | 效果 |
|---|---|---|
| **软补偿** | 对账脚本定期比对 Redis 与 DB，发现差值时**以 DB 为准重新预热** | 修复偏差 |
| **不处理** | 允许少量泄漏 | 必须在报告里写明 |

选**软补偿**，并提供一个 SQL 查询让差值可见（§10）。

**为什么这不能靠"加重试"解决**：崩溃发生在 `finally` 之前，
任何代码都执行不到。这是**进程内方案的天花板**——也正是 v3 要用 MQ 的原因
（消息持久化在外，消费方可以从崩溃中恢复）。

**给面试讲的话**：「v2 在进程内做补偿，遇到进程崩溃就漏，所以 v3 必须引入消息队列
把'要扣库存'这件事持久化到进程之外。」——这句话说明你理解每一版的天花板在哪。

### 9.5 场景 5：Redis 挂了

两种策略：

| 策略 | 行为 | 后果 |
|---|---|---|
| **快失败** | Lua 抛异常 → 500 | 全站不可用，但**不会超卖** |
| **降级到 DB** | catch 异常 → 直接走 v1 的 MySQL 逻辑 | 可用，但退化到 v1 的性能 |

**本项目选择：快失败（并且 500 要带明确提示）。**

理由：
1. **降级路径未被测试覆盖**，压测时切过去等于引入未验证的代码
2. **"降级到 DB"听起来美好，实际会让库存错误**：Redis 里的库存可能是
   "DB 已扣但 Redis 没扣"的陈旧值，降级后两边会持续偏离
3. 这个项目的定位是**学习并发控制**，不是做高可用——§17 明确不做熔断降级

**但必须实测**：把 Memurai 停掉，确认返回的是清晰的 500 而不是诡异的空指针（§13 第 9 项）。

---

## 10. 对账

### 10.1 判定 SQL

```sql
-- Redis 里的余量（用 memurai-cli 查）
-- GET seckill:stock:88

-- DB 里的余量
SELECT stock, sold, capacity, stock + sold AS total
FROM seckill.course_stock WHERE course_id = 88;
```

**三个数字必须满足**：

| 条件 | 含义 |
|---|---|
| `redis_stock = db_stock` | 两边同步 |
| `db_stock + db_sold = capacity` | DB 自洽 |
| `db_sold = success_orders` | 订单数与库存变化对应 |

**任何一条不满足，就是不一致**，必须在报告里记录，不能"重跑一次看看"。

### 10.2 提供对账接口

`GET /api/admin/cache/stock/{courseId}` 返回：

```json
{
  "code": 200,
  "data": {
    "courseId": 88,
    "redisStock": 40,
    "dbStock": 40,
    "dbSold": 60,
    "capacity": 100,
    "consistent": true
  }
}
```

`consistent` 的判定：`redisStock == dbStock`。

**这个接口比一段 SQL 脚本更有用**，因为：
- 压测脚本里可以直接调它做断言
- 面试时可以当场演示"我怎么证明两边一致"

### 10.3 对账脚本

`loadtest/reconcile.ps1`（新建）：

```powershell
# 输出 Redis 与 DB 的差值，非 0 时退出码 1
# 用法：.\reconcile.ps1 -CourseId 88
```

对 `reset.ps1` 的改动（**漏了这条 v2 全废**）：

```powershell
# 原来只有 DB
UPDATE seckill.course_stock SET stock = capacity, sold = 0, version = 0 WHERE course_id = $CourseId;

# v2 必须同时清 Redis，否则 Lua 读到的是上一轮的旧值
& "D:\tools\Redis\memurai-cli.exe" del "seckill:stock:$CourseId"
```

**如果忘了清 Redis**：DB 复位成 100，Redis 还是 0（上一轮卖光了）→
下一轮所有请求都返回「名额已满」，你会以为 v2 坏了。

---

## 11. 压测方案

### 11.1 四组对比（沿用 v1 的场景）

同一场景：课程 88，`capacity=100`，300 VU × 300 学生，每人 1 次。

| 版本 | 这是第几组 |
|---|---|
| v0-baseline | 基线（已有数据） |
| v1-optimistic | v1 最好的（已有数据） |
| **v2-redis-lua** | **本阶段** |
| v1-version-cas | 对照（已有数据） |

每版跑 3 轮取中位数。**不要只跑一轮挑好看的**。

### 11.2 🔴 必须证明"门禁真的挡住了"

只看 P95 变快是不够的——**变快可能是因为别的原因**。
必须直接测量"MySQL 少挨了多少次 UPDATE"。

方法：压测前后各取一次 `Com_update` 全局计数：

```sql
SHOW GLOBAL STATUS LIKE 'Com_update';
```

| 版本 | `Com_update` 增量（预期） | 说明 |
|---|---|---|
| v1-optimistic | ~300 | 300 个请求都执行了 UPDATE |
| **v2-redis-lua** | **~100** | 200 个在 Redis 就被挡回 |
| v0-baseline | ~300 | 同 v1 |

**如果 v2 的 `Com_update` 还是 300，说明 Redis 门禁没生效**——
哪怕 P95 看着更快，也是假象。

这一条是**本阶段最重要的验收项**，因为它直接验证"机制"而不是"结果"。

> 注意：`Com_update` 是全库累计值，压测期间别的连接如果也在 UPDATE 会污染。
> 压测前确认没有其他程序在写这个库（IDEA 的数据库窗口、其他服务）。

### 11.3 预期数据

| 指标 | v1-optimistic（实测） | v2 预期 | 理由 |
|---|---|---|---|
| 成功单 | 100 | **100** | 不能变 |
| 超卖 | 0 | **0** | 不能变 |
| 守恒 | ✅ 100 | ✅ 100 | 不能变 |
| P95 | 339 ms | **< 200 ms** | 200 个请求在 Redis 就返回 |
| P99 | 344 ms | **< 250 ms** | 同上 |
| `Com_update` | ~300 | **~100** | 门禁生效 |

**先写下你的预测，跑完再填实测。** 差的偏差比数字本身更值钱。

### 11.4 版本切换

```powershell
# 四个值：v0-baseline / v1-optimistic / v1-pessimistic /
#        v1-version-cas / v2-redis-lua
.\mvnw.cmd "-s" "D:\develop\apache-maven-3.9.15\conf\settings.xml" -B -ntp -o `
  "-Daether.syncContext.named.factory=noop" spring-boot:run `
  "-Dspring-boot.run.arguments=--seckill.strategy=v2-redis-lua"
```

**不改 `application.yaml`**——改了会忘记改回来，下次启动就悄悄跑错版本。

---

## 12. 预期 vs 实测

跑完填这张表（我会在验收时一起对）：

| 指标 | 预期 | 实测（第 1 轮） | 实测（第 2 轮） | 实测（第 3 轮） |
|---|---|---|---|---|
| 成功单 | 100 | | | |
| `biz_sold_out` | 200 | | | |
| `biz_other` | 0 | | | |
| 超卖 | 0 | | | |
| `stock + sold` | 100 | | | |
| Redis 余量 | 0 | | | |
| `Com_update` 增量 | ~100 | | | |
| avg | | | | |
| P95 | < 200 ms | | | |
| P99 | < 250 ms | | | |

---

## 13. 12 项自测

按顺序做，每项都要有实际输出（不能"我觉得应该没问题"）。

| # | 测试 | 期望 | 关键点 |
|---|---|---|---|
| 1 | 编译 | `BUILD SUCCESS` | 看 BUILD SUCCESS，不看 exit code |
| 2 | 启动 + 预热 | Redis 里 `seckill:stock:88` = `100` | 用 `memurai-cli get` 确认 |
| 3 | 单次抢课 | 200，`orderNo` 有值 | |
| 4 | 立即查两边 | Redis 99 / DB `stock=99 sold=1` / 订单 1 | 三个数字都要对 |
| 5 | 同一学生再抢 | 409「你已选过该课程」 | **且 Redis 必须还是 99**（归还了） |
| 6 | **卖 60 个后重启** | Redis 恢复成 40，不是 100 | 🔴 最容易错，见 §5.3 |
| 7 | 卖光后再抢 | 409「该课程名额已满」 | Redis 余量 0，**DB 不再收到 UPDATE** |
| 8 | 退课 | 200，Redis +1，DB `stock+1 sold-1` | 两边同步 |
| 9 | **停掉 Redis 抢课** | 500 + 明确提示 | 不能是 NPE，不能假装成功 |
| 10 | 重启 Redis 后抢课 | 自动预热并成功 | 按需预热生效 |
| 11 | 未预热时抢课（`del` key） | 自动预热后成功 | 不能直接报「未预热」给用户 |
| 12 | 对账接口 | `consistent: true` | |

**第 6 项一定要做**：把 `stock` 改成 40（模拟已卖 60），重启服务，
然后 `memurai-cli get seckill:stock:88` 看是不是 `40`。
如果是 `100`，说明你用了 `capacity`——这个 bug 只有这一步能抓到。

**第 9 项一定要做**：停 Memurai 后请求，看返回什么。
如果是 `NullPointerException` 的堆栈泄漏给客户端，说明异常处理有洞。

---

## 14. 验收标准

全部满足才算完成：

- [ ] `clean compile` BUILD SUCCESS，`clean test` 全绿（v1 的 3 个测试也要绿）
- [ ] 300 VU × 3 轮：`biz_success=100`、超卖 0、`stock+sold=100`、Redis 余量 0
- [ ] **`Com_update` 增量 ≈ 100**（不是 300）
- [ ] P95 < 339 ms（比 v1-optimistic 快，否则这一版没意义）
- [ ] 12 项自测全过，重点是第 6 项（预热用 `stock`）和第 9 项（Redis 挂了的表现）
- [ ] 对账接口在压测后返回 `consistent: true`
- [ ] 五版对比表写进 `docs/压测报告-v2.md`
- [ ] 提交 + tag `v2-redis-lua`

**关于 P95 的门槛**：如果实测没到 200 ms 但低于 339 ms，**也算通过**，
但要在报告里写下真实数字和原因分析。**不许为了达标去调参凑数。**

---

## 15. 排查顺序

出问题时按这个顺序，**不要跳**：

| # | 症状 | 先查什么 |
|---|---|---|
| 1 | 全部 409「名额已满」 | `memurai-cli get seckill:stock:88` —— 大概率 `reset.ps1` 没清 Redis |
| 2 | 全部 500 | Redis 是否在跑（`ping`）；`curl /api/course/88` 看 MySQL 通不通 |
| 3 | `NOSCRIPT` 报错 | 是否手写了 `EVALSHA` 而没用 `DefaultRedisScript` |
| 4 | `memurai-cli get` 查不到 key | 用了 `RedisTemplate` 而不是 `StringRedisTemplate`（key 被二进制化） |
| 5 | 成功单 < 100 但没报错 | 对账：Redis 余量和 DB 余量差多少；grep 日志「归还失败」 |
| 6 | 成功单 > 100（超卖） | 🔴 门禁没生效。查 `Com_update` 是否还是 300 |
| 7 | P95 没降 | 同 6，先证明门禁生效再看性能 |
| 8 | 压测中途大面积失败 | Memurai 是不是自己退出了（`Get-Process memurai`） |

**第 6 项和第 7 项的关系**：性能没提升，先怀疑机制没生效，不要先怀疑"Redis 不够快"。

---

## 16. 必踩的坑

### 16.1 🔴 `reset.ps1` 没清 Redis

已写进 §10.3。**这是最高频的坑**，症状是"下一轮全部名额已满"，
你会以为是代码坏了，其实是上一轮的库存还留着。

### 16.2 🔴 用 `capacity` 预热

见 §5.3。**第一个用例发现不了**，必须专门测第 6 项。

### 16.3 `finally` 里归还的条件写反

```java
// ❌ 无条件归还：卖出去的又放回去了，等于永不缺货
finally { stockCacheService.increment(courseId); }

// ✅ 只在失败时归还
finally { if (needRollback) stockCacheService.increment(courseId); }
```

**症状**：压测显示 `biz_success=300`（超卖 200），因为名额被不断归还。

### 16.4 归还了两次

`tryDecrement` 失败时归还一次，异常被外层 catch 又归还一次。
**症状**：Redis 余量变成 102、103……超过 `capacity`。

防法：用一个 `boolean redisDecremented` 标记，**只在一个地方归还**。

### 16.5 `@Transactional` 和 Redis 混用

```java
@Transactional
public SeckillResponse seckill(...) {
    stockCacheService.tryDecrement(courseId);   // Redis 不受事务管
    // ... MySQL 操作
    // 如果这里抛异常，MySQL 回滚，但 Redis 不回滚
}
```

`@Transactional` **只管数据库连接，完全不知道 Redis 的存在**。
Redis 的"回滚"必须自己写（`finally` + `increment`）。

### 16.6 Lua 里 `tonumber` 没判 nil

见 §6.3 错误 4。**症状**：key 不存在时抛 Lua 错误，500，看不出原因。

### 16.7 用 `RedisTemplate` 导致 key 二进制化

见 §8.1。**症状**：代码全对，但 `memurai-cli` 查不到 key，对账做不了。

### 16.8 `execute` 返回 `null` 没判

见 §8.3 第 2 点。**症状**：`NullPointerException`，位置看起来莫名其妙（拆箱处）。

### 16.9 Memurai 自己退出

见 §1.2。**症状**：压测跑一半大面积 500，日志刷 Redis 连接拒绝。
**这不是代码 bug，先 `Get-Process memurai` 确认。**

### 16.10 忘了加 `@PreAuthorize`

`CacheAdminController` 如果不加 `@PreAuthorize("hasRole('ADMIN')")`，
任何学生都能把库存重置成 100。**这是安全事故，不是小 bug。**

---

## 17. 明确不做

划清边界，防止范围蔓延：

| 不做 | 为什么 | 什么时候做 |
|---|---|---|
| ❌ 消息队列 | 那是 v3 的主题 | v3 |
| ❌ 分布式锁（Redisson） | 单机不需要；锁的问题 v1 已经证明 MySQL 行锁够用 | 不需要 |
| ❌ 熔断 / 降级到 DB | 降级路径没测过，引入未验证的代码（§9.5） | 不需要 |
| ❌ 限流（Sentinel / 令牌桶） | 值得做，但会混淆"Redis 挡请求"的功劳归属 | v4 |
| ❌ `@Cacheable` 缓存课程信息 | 与本阶段主题无关 | v4 |
| ❌ Redis Cluster | 单机，没有多节点环境 | 不需要 |
| ❌ 多级缓存（Caffeine + Redis） | 同上 | 不需要 |
| ❌ 改 `CourseStockMapper.tryDecrement` | 复用 v1 的，改了历史数据不可比 | 不需要 |
| ❌ 改 v0/v1 的实现 | 要能随时切回去做对比 | 不需要 |
| ❌ 前端页面 | 阶段 7 再统一做 | 阶段 7 |

---

## 18. 面试话术准备

### Q1：为什么用 Redis 而不是直接让 MySQL 扛？

> 不是"MySQL 扛不住"，是**没必要让它扛注定失败的请求**。
> 300 个请求抢 100 个名额，200 个注定失败。这些失败在数据库里要经历
> 一次连接、一条 UPDATE、一次条件判断、一次回滚——全部白做。
> 放到 Redis 里，200 个在内存里就被挡回，MySQL 只看到 100 次 UPDATE。
> 我实测 `Com_update` 从 300 降到 100，P95 从 339 ms 降到 ___ ms。

**关键：用 `Com_update` 这个指标证明"挡掉了"，而不只是"变快了"。**

### Q2：Redis 的原子性怎么保证的？

> Redis 是单线程执行命令的。一段 Lua 脚本作为**一条命令**执行，
> 中间不会插入其他客户端的命令，所以脚本里的"读-判断-写"是原子的。
> 这和 MySQL 用 `WHERE stock > 0` 把三步压进一条 SQL 是一样的思路——
> 手段不同，本质都是"把判断和修改变成一个不可分割的操作"。
>
> 反过来，如果用两条独立命令（先 GET 再 DECR）就不原子，
> 那就是 v0 丢失更新的 Redis 版本。

### Q3：Redis 和 MySQL 不一致怎么办？

> 分两类。
>
> **可以恢复的**：代码执行到了 `finally`，归还 Redis 成功，两边一致。
> 这覆盖了绝大多数的失败路径——条件更新失败、重复订单、INSERT 冲突。
>
> **不能恢复的**：进程在 `DECR` 之后、`INSERT` 之前被杀。`finally` 来不及执行，
> 那个名额就永久丢在 Redis 里。这是进程内补偿的天花板。
> 我的处理是对账：比对 Redis 余量和 DB 余量，不一致就以 DB 为准重新预热。
>
> 正因如此，v3 必须引入消息队列——把"要扣库存"这件事持久化到进程之外，
> 消费者可以从崩溃中恢复。这是版本演进的必然，不是我随便加的。

### Q4：如果 Redis 挂了，系统还能用吗？

> 我选择**快失败**，返回 500，而不是降级到数据库。
>
> 两个理由。第一，降级路径没有被测试覆盖，压测时切过去等于上线未验证的代码。
> 第二，更重要——**降级会让库存错得更离谱**。Redis 里的库存可能已经和 DB 偏离，
> 降级后两边会持续double-count。
>
> 真正的生产系统应该在这里加熔断和灰度降级，但那需要先有监控和演练。
> 我这个阶段的定位是把并发控制讲清楚，不假装自己有高可用。

### Q5：这一版还有什么问题？

> 三个。
>
> 一，**进程崩溃会漏**，见 Q3。
> 二，**强依赖 Redis**：Redis 一挂全站不可用。这是用可用性换性能。
> 三，**库存预扣和订单写入是两次独立操作**，中间有窗口。

---

## 19. 交付物

完成这一阶段后应该有：

| 交付物 | 路径 |
|---|---|
| 6 个 Java 文件 + 1 个测试 | 见 §4.1 |
| 压测报告 | `docs/压测报告-v2.md` |
| 对账脚本 | `loadtest/reconcile.ps1` |
| 修改后的复位/校验脚本 | `loadtest/reset.ps1`、`verify.ps1` |
| git 提交 + tag | `v2-redis-lua` |

**报告的必备章节**（对照 v1 的报告结构）：

1. 五版对比表（v0 / v1-optim / v1-pessim / v1-cas / v2）
2. **`Com_update` 对比**——证明门禁生效
3. 一致性验证：五个场景各自怎么验的、结果如何
4. 崩溃场景：如果你做了 `kill -9` 实验，把偏差数字写下来
5. 已知局限（尤其是"Redis 强依赖"和"崩溃会漏"）
6. 三个反直觉发现（如果有）

---

## 20. 开始之前

三件事先做：

1. **确认 Redis 在跑**：`memurai-cli.exe ping` → `PONG`
2. **确认当前在 `v1-db-lock` 标签上**：`git log --oneline -1`
3. **先跑一次 v1-optimistic 的基线**（如果你没保留数据），
   因为 v2 的所有对比都是相对它的

然后按 §4.1 建文件，从 `StockLuaScript` 和 `StockCacheService` 开始——
**先让 Lua 能跑通，再往 Service 里接**。不要一次性写完 6 个文件再编译。

建议的实现顺序：

```
1. StockLuaScript（常量）+ RedisConfig（脚本 Bean）
   → 写个临时测试类调 execute，确认返回 2/1/0/-1/-2
2. StockCacheService + Impl（预热 / 扣减 / 归还 / 查询）
   → 用 CacheAdminController 调，memurai-cli 验证
3. SeckillServiceImplV2RedisLua（业务）
   → 单次抢课跑通
4. 补偿路径（§7.3）
   → 重复抢课、并发抢课
5. 压测
```
