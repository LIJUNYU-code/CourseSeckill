-- A8 分表实验：完整复现脚本
-- 目标：在 2000 万行量级下，对比「单表」与「4 分片」的查询代价
-- 分片键 student_id，4 片：shard = student_id % 4
--
-- 用法（会创建独立库 seckill_a8，不碰业务库 seckill）：
--   mysql -u root -p --default-character-set=utf8mb4 < V10__a8_sharding_split.sql
--
-- 实测耗时：造数 71 分钟 + 分表 36 分钟（每片 17~19 分钟）
-- 磁盘占用：单表 4 GB + 分片表 4 GB = 约 8 GB
-- 清理：DROP DATABASE seckill_a8;

-- ============================================================
-- 一、建库与表结构
-- ============================================================

CREATE DATABASE IF NOT EXISTS seckill_a8 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE seckill_a8;

-- 与业务表结构完全一致（含 4 个索引：uk_order_no / uk_student_course /
-- idx_course_status / idx_student_status），否则测出来的数据没有可比性
DROP TABLE IF EXISTS seckill_order;
CREATE TABLE seckill_order (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no    VARCHAR(40)  NOT NULL COMMENT '订单号',
    student_id  BIGINT       NOT NULL COMMENT '学生ID',
    course_id   BIGINT       NOT NULL COMMENT '课程ID',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 1成功 2失败 3已退课',
    fail_reason VARCHAR(200) DEFAULT NULL COMMENT '失败原因',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_student_course (student_id, course_id),
    KEY idx_course_status (course_id, status),
    KEY idx_student_status (student_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'A8 实验订单表';

-- ============================================================
-- 二、造 2000 万行
--
-- 🔴 关键约束：uk_student_course (student_id, course_id) 把行数上限
--    钉死在「学生数 × 课程数」。
--    10000 学生 × 100 课 = 100 万行上限，造不出 2000 万。
--    所以必须放大到 20 万学生 × 100 课 = 2000 万行。
-- ============================================================

DROP TABLE IF EXISTS seq10;
CREATE TABLE seq10 (n INT PRIMARY KEY);
INSERT INTO seq10 VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

-- 10^5 = 10 万个序号
DROP TABLE IF EXISTS seq100k;
CREATE TABLE seq100k (n INT PRIMARY KEY AUTO_INCREMENT);
INSERT INTO seq100k (n)
SELECT a.n*10000 + b.n*1000 + c.n*100 + d.n*10 + e.n + 1
FROM seq10 a, seq10 b, seq10 c, seq10 d, seq10 e;

-- 20 万学生 id
-- ⚠️ 注意：seq100k 只有 10 万行，直接 WHERE n <= 200000 只能取到 10 万。
--    必须用两层笛卡尔积凑到 20 万，否则造出来只有 1000 万行。
DROP TABLE IF EXISTS seq_student;
CREATE TABLE seq_student (n INT PRIMARY KEY);
INSERT INTO seq_student
SELECT (a.n - 1) * 2 + b.n + 1 FROM seq100k a, seq10 b WHERE b.n < 2;

-- 100 门课
DROP TABLE IF EXISTS dim_course;
CREATE TABLE dim_course (c INT PRIMARY KEY);
INSERT INTO dim_course SELECT a.n*10 + b.n + 1 FROM seq10 a, seq10 b;

-- 逐门课灌 20 万行。用存储过程包住，避免手工执行 100 次。
-- 实测：第 1 批 11.6 秒，第 100 批 54.9 秒（超线性，因为索引维护 + buffer pool 溢出）
DROP PROCEDURE IF EXISTS build_a8_data;
DELIMITER $$
CREATE PROCEDURE build_a8_data()
BEGIN
    DECLARE c INT DEFAULT 1;
    WHILE c <= 100 DO
        INSERT INTO seckill_order (order_no, student_id, course_id, status)
        SELECT CONCAT('A8', LPAD(s.n, 7, '0'), LPAD(c, 3, '0')), s.n, c, 1
        FROM seq_student s;
        SET c = c + 1;
    END WHILE;
END$$
DELIMITER ;

CALL build_a8_data();
DROP PROCEDURE build_a8_data;

-- 校验：必须是 20000000
SELECT CONCAT('造数完成 rows=', COUNT(*)) AS check_rows FROM seckill_order;

-- 真实体积（information_schema.tables 对本表可能显示 0，必须查 INNODB_TABLESPACES）
SELECT CONCAT('FILE_SIZE=', ROUND(FILE_SIZE/1024/1024), 'MB') AS check_size
FROM information_schema.INNODB_TABLESPACES
WHERE name = 'seckill_a8/seckill_order';

-- ============================================================
-- 三、建 4 张分片表并分布数据
--
-- 实测每片 17~19 分钟。若只想验证规律，造 2 片即可：
-- 分表对非分片键查询的成本 = 各片成本之和（线性），2 片结果 × 2 就是 4 片成本。
-- ============================================================

DROP TABLE IF EXISTS seckill_order_0;
DROP TABLE IF EXISTS seckill_order_1;
DROP TABLE IF EXISTS seckill_order_2;
DROP TABLE IF EXISTS seckill_order_3;

CREATE TABLE seckill_order_0 LIKE seckill_order;
CREATE TABLE seckill_order_1 LIKE seckill_order;
CREATE TABLE seckill_order_2 LIKE seckill_order;
CREATE TABLE seckill_order_3 LIKE seckill_order;

INSERT INTO seckill_order_0 SELECT * FROM seckill_order WHERE student_id % 4 = 0;
INSERT INTO seckill_order_1 SELECT * FROM seckill_order WHERE student_id % 4 = 1;
INSERT INTO seckill_order_2 SELECT * FROM seckill_order WHERE student_id % 4 = 2;
INSERT INTO seckill_order_3 SELECT * FROM seckill_order WHERE student_id % 4 = 3;

-- 校验：四片之和必须等于原表（20000000）
SELECT CONCAT('原表=', (SELECT COUNT(*) FROM seckill_order),
              '  分片和=', (SELECT COUNT(*) FROM seckill_order_0) + (SELECT COUNT(*) FROM seckill_order_1)
                        + (SELECT COUNT(*) FROM seckill_order_2) + (SELECT COUNT(*) FROM seckill_order_3)) AS check_sum;

-- ============================================================
-- 四、测量（示例，完整对比见 docs/A8-分表2000万行实测报告.md §4）
--
-- ⚠️ 必须用 SET profiling + SHOW PROFILES 读服务端耗时。
--    用客户端计时（Stopwatch 包 mysql.exe）会被 ~100ms 的进程启动淹没。
-- ============================================================

SET profiling = 1;
SET profiling_history_size = 500;

-- 快：主键
SELECT * FROM seckill_order WHERE id = 15000000;
-- 快：命中分片键（uk_student_course）
SELECT * FROM seckill_order WHERE student_id = 150000 AND status = 1;
-- 快：order_no 唯一索引
SELECT * FROM seckill_order WHERE order_no = 'A81500000001';
-- 快：首页
SELECT * FROM seckill_order WHERE course_id = 50 AND status = 1 LIMIT 20;
-- 慢：深分页（约 270ms）
SELECT * FROM seckill_order WHERE course_id = 50 AND status = 1 LIMIT 100000, 20;
-- 慢：COUNT（约 79ms）
SELECT COUNT(*) FROM seckill_order WHERE course_id = 50 AND status = 1;

SHOW PROFILES;

-- 根因分析：看清「预估扫描行数」而不是凭感觉
EXPLAIN SELECT * FROM seckill_order WHERE course_id = 50 AND status = 1 LIMIT 20;
-- 输出 rows=398820 → 慢的不是表大，是这门课有 39.8 万人

-- ============================================================
-- 五、对照实验：游标分页 vs offset 分页
-- ============================================================

SELECT id INTO @lastId FROM seckill_order WHERE course_id = 50 AND status = 1 ORDER BY id LIMIT 100000, 1;

-- 约 280ms
SELECT * FROM seckill_order WHERE course_id = 50 AND status = 1 LIMIT 100000, 20;
-- 约 0.2ms，快 1000 倍
SELECT * FROM seckill_order WHERE course_id = 50 AND status = 1 AND id > @lastId ORDER BY id LIMIT 20;

-- ============================================================
-- 六、验证跨分片唯一索引失效（这是分表最严重的代价）
-- ============================================================

DELETE FROM seckill_order_0 WHERE order_no = 'DUP_TEST_001';
DELETE FROM seckill_order_1 WHERE order_no = 'DUP_TEST_001';

-- 同一个 order_no，分别落两个分片，两次都成功
-- 注意 course_id 用 9991 这种不存在的值，否则会先撞上 uk_student_course
INSERT INTO seckill_order_0 (order_no, student_id, course_id, status) VALUES ('DUP_TEST_001', 150000, 9991, 1);
INSERT INTO seckill_order_1 (order_no, student_id, course_id, status) VALUES ('DUP_TEST_001', 150001, 9991, 1);

-- 结果：两片各 1 行 → 全局唯一性失效
SELECT CONCAT('片0=', (SELECT COUNT(*) FROM seckill_order_0 WHERE order_no='DUP_TEST_001'),
              '  片1=', (SELECT COUNT(*) FROM seckill_order_1 WHERE order_no='DUP_TEST_001'),
              '  → 同号跨片共存') AS check_unique;

-- 反证：同片内重复会被拦住
-- ⚠️ 下面这条**故意会报错**，这是预期结果，不是脚本坏了：
--    ERROR 1062 (23000): Duplicate entry 'DUP_TEST_001' for key 'seckill_order_0.uk_order_no'
--    它证明唯一索引在「同一分片内」仍然有效，失效的只是「跨分片」。
INSERT INTO seckill_order_0 (order_no, student_id, course_id, status) VALUES ('DUP_TEST_001', 150004, 9992, 1);

-- 清理测试数据
DELETE FROM seckill_order_0 WHERE order_no = 'DUP_TEST_001';
DELETE FROM seckill_order_1 WHERE order_no = 'DUP_TEST_001';

-- ============================================================
-- 七、清理（释放约 8 GB）
-- ============================================================

-- DROP DATABASE seckill_a8;
