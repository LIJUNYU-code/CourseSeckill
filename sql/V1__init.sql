-- ===================================================================
-- CourseSeckill 高并发选课引擎 —— 建库建表脚本
-- ===================================================================
-- 执行方式（在项目根目录）：
--   mysql -u root -p -e "source sql/V1__init.sql"
--
-- 设计说明见 docs/项目开发文档.md §3。这里只保留"为什么这么写"的必要注释，
-- 完整的取舍论证在文档里。
-- ===================================================================

-- Windows 上 mysql 客户端的默认字符集常常是 gbk，而库表都是 utf8mb4。
-- 不加这一行的话，脚本里的中文会被按 gbk 解读而变成更长的字节序列，
-- 报出 "Data too long for column" 这种看起来像表结构问题的假错误。
-- SET NAMES 之后本会话的三层字符集都统一为 utf8mb4，不再依赖客户端默认值。
SET NAMES utf8mb4;

DROP DATABASE IF EXISTS seckill;
CREATE DATABASE seckill
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE seckill;

-- -------------------------------------------------------------------
-- 1. 学生表
-- -------------------------------------------------------------------
-- 为什么单独一张表而不是复用项目一的 student：
--   项目一是业务项目，学生有 20 多个字段；这里是压测项目，
--   学生只是"一个能登录、能抢课的账号"，字段越少，造 1 万条数据越快。
-- student_no 唯一：登录靠它，重名合法但学号不能重复。
CREATE TABLE student (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    student_no  VARCHAR(20)  NOT NULL                COMMENT '学号，登录账号',
    name        VARCHAR(50)  NOT NULL                COMMENT '姓名',
    password    VARCHAR(100) NOT NULL                COMMENT 'BCrypt 哈希',
    status      TINYINT      NOT NULL DEFAULT 1      COMMENT '1正常 0禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_student_no (student_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '学生';

-- -------------------------------------------------------------------
-- 2. 课程表（只存课程信息，不存库存）
-- -------------------------------------------------------------------
-- 库存为什么不在这个表：
--   抢课是一条 UPDATE ... WHERE stock > 0，会给这一行加行锁。
--   如果课程名、教师也在这行，那么"查课程列表"和"抢库存"会互相阻塞——
--   读请求被写请求的行锁拖住，压测时分不清是锁的问题还是别的问题。
--   拆出去之后，课程表是纯读表，抢课只锁 course_stock 那一行。
CREATE TABLE course (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    course_name VARCHAR(100)  NOT NULL                COMMENT '课程名',
    teacher     VARCHAR(50)   NOT NULL                COMMENT '授课教师',
    credit      DECIMAL(3, 1) NOT NULL DEFAULT 2.0    COMMENT '学分',
    start_time  DATETIME      NOT NULL                COMMENT '选课开始时间',
    end_time    DATETIME      NOT NULL                COMMENT '选课结束时间',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_start_time (start_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课程';

-- -------------------------------------------------------------------
-- 3. 课程库存表（与 course 一对一）
-- -------------------------------------------------------------------
-- stock + sold = capacity 这条恒等式是本项目最重要的校验：
--   每次压测跑完，必须手工核对这三者关系。等式不成立就说明
--   "扣减"和"写订单"之间出现了不一致（比如 MQ 丢消息、事务没回滚）。
--   §6.2 的 k6 脚本会自动跑这个检查。
--
-- CHECK (stock >= 0)：数据库层的最后防线。
--   v0 版本故意不加任何并发控制，靠这个约束来"暴露"超卖——
--   超卖发生时插入会失败，你会在日志里看到真实数据，比任何说教都直观。
CREATE TABLE course_stock (
    course_id   BIGINT   NOT NULL COMMENT '课程 id，与 course 一对一',
    capacity    INT      NOT NULL COMMENT '总名额，初始化后不再变',
    stock       INT      NOT NULL COMMENT '剩余名额',
    sold        INT      NOT NULL DEFAULT 0 COMMENT '已售出数量',
    version     INT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（v1 使用）',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (course_id),
    CONSTRAINT ck_stock_non_negative CHECK (stock >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课程库存';

-- -------------------------------------------------------------------
-- 4. 抢课订单表
-- -------------------------------------------------------------------
-- uk_student_course 唯一索引：同一学生同一课程只能有一条记录。
--   这是"重复下单"的最终防线——并发下 Service 层查重一定会漏
--   （两个请求同时查到"没有记录"，然后同时插入），唯一索引不会漏。
--
-- 为什么 status 用数字而不是 ENUM：
--   ENUM 在加值时需要 ALTER TABLE，压测期间改表结构是灾难。
--   数字 + 注释的方式更灵活，代价是可读性差一点（已在实体类里用常量兜住）。
--
-- 为什么退课用 UPDATE status=3 而不是 DELETE：
--   如果 DELETE 掉记录，学生重新抢课时 uk_student_course 不会阻止（因为记录没了），
--   但如果你想让"退课记录"可追溯，DELETE 之后就查不到了。
--   更麻烦的是：有些实现会在退课时把库存加回去，DELETE + 重复 INSERT 的组合
--   很容易写出"库存加回去了但订单还留着"的脏数据。
CREATE TABLE seckill_order (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no    VARCHAR(40) NOT NULL                COMMENT '订单号，对外暴露的唯一标识',
    student_id  BIGINT      NOT NULL                COMMENT '学生 id',
    course_id   BIGINT      NOT NULL                COMMENT '课程 id',
    status      TINYINT     NOT NULL DEFAULT 0      COMMENT '0待处理 1成功 2失败 3已退课',
    fail_reason VARCHAR(200)         DEFAULT NULL   COMMENT '失败原因，便于统计',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_student_course (student_id, course_id),
    KEY idx_course_status (course_id, status),
    KEY idx_student_status (student_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抢课订单';

-- -------------------------------------------------------------------
-- 5. 系统用户表（管理员 / 学生统一登录入口）
-- -------------------------------------------------------------------
-- 为什么学生表之外还要 sys_user：
--   管理员不适合塞进 student 表（学生表有学号）。
--   但也可以说：干脆一张 user 表搞定。这里选择分开，是因为
--   "压测的 1 万学生"和"几个管理员"的数据量、访问模式完全不同，
--   混在一起会让造数据和清理都变复杂。
CREATE TABLE sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username    VARCHAR(50)  NOT NULL                COMMENT '登录名',
    password    VARCHAR(100) NOT NULL                COMMENT 'BCrypt 哈希',
    real_name   VARCHAR(50)  NOT NULL                COMMENT '显示名',
    role        VARCHAR(20)  NOT NULL                COMMENT 'ADMIN / STUDENT',
    status      TINYINT      NOT NULL DEFAULT 1      COMMENT '1正常 0禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统用户';

-- -------------------------------------------------------------------
-- 6. 消息消费日志表（阶段 5 幂等去重用）
-- -------------------------------------------------------------------
-- msg_id 唯一索引是"消息幂等"的实现载体：
--   RabbitMQ 保证至少一次投递（at-least-once），意味着同一条消息可能被消费多次。
--   去重做法：处理消息的第一步就 INSERT 一条 msg_id；如果 DuplicateKeyException，
--   说明这条消息处理过了，直接 ack 跳过。
--
-- 为什么去重表和业务表必须在同一个事务里（阶段 5 会重点讲）：
--   如果先 INSERT 去重记录再扣库存，中间挂了 → 消息重投时发现"去重记录已存在"→ 跳过，
--   库存永远没扣。反过来先扣库存再记去重，中间挂了 → 重投时库存被扣两次。
--   两种顺序在不加事务时都有 bug，这才是"消息幂等"真正难的地方。
CREATE TABLE mq_consume_log (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    msg_id      VARCHAR(64) NOT NULL                COMMENT '消息唯一标识',
    status      TINYINT     NOT NULL DEFAULT 0      COMMENT '0处理中 1成功 2失败',
    error_msg   VARCHAR(500)         DEFAULT NULL   COMMENT '失败原因',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息消费日志（幂等去重）';

-- -------------------------------------------------------------------
-- 管理员账号（密码 Demo@2026）
-- -------------------------------------------------------------------
-- 这是硬编码的公开演示凭据，仅用于本地开发和演示。
-- 真实环境必须改密码，做法见 README 的"安全提醒"。
INSERT INTO sys_user (username, password, real_name, role, status)
VALUES ('admin',
        '$2a$10$XV8AaxjOFQIn4FIsZs3ceeXqAQtXyOteqox9RBOW5B7z7Jx25OT8G',
        '管理员', 'ADMIN', 1);
