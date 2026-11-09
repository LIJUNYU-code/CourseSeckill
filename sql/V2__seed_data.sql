-- ===================================================================
-- 造测试数据（阶段 1 用；阶段 8 分表实验会另写一个大批量版本）
-- ===================================================================
-- 目标数据量（文档 §3.4 的"常规压测"档）：
--   1 万学生、100 门课、每门 100 个名额、1 万条学生账号
--
-- 执行：
--   mysql -u root --default-character-set=utf8mb4 -e "source sql/V2__seed_data.sql"
--
-- ⚠️ 这个脚本会先清空业务表，只在本地开发/压测环境使用。
-- ===================================================================

SET NAMES utf8mb4;
USE seckill;

-- -------------------------------------------------------------------
-- 关键技巧：复用同一个 BCrypt 哈希
-- -------------------------------------------------------------------
-- BCrypt 每次哈希约 100ms（cost=10）。1 万个账号单独哈希要 16 分钟以上，
-- 而且每次造数据都要等一遍，完全不可接受。
--
-- 但 BCrypt 的验证不依赖"这条记录的 salt 是现场生成的"——
-- salt 是嵌在哈希串自身的 $2a$10$<22字符salt><31字符hash> 里的。
-- 验证时用哈希串里的 salt 重算，所以同一个哈希串对同一个密码永远验证通过。
--
-- 结论：所有测试账号共用下面这一个哈希（对应密码 Abc12345），验证照常成功。
-- 代价：数据库里的哈希全都一样，不能用它验证"不同密码登录失败"这类场景——
--       那种场景用 admin 账号（密码 Demo@2026）来测即可。
--
-- 本哈希的明文是 Abc12345，可用于所有压测脚本。
-- 该哈希已用 BCryptPasswordEncoder.matches("Abc12345", hash) 实测返回 true，
-- 不是从别处抄来的示例值（网上的示例哈希经常和声称的密码对不上）。
SET @pwd_hash = '$2a$10$4geBumUPLbC5mEzw2.U5G.rNh5gEYOerQ0mjIKEvkuAoyDDSJGiFC';

TRUNCATE TABLE seckill_order;
TRUNCATE TABLE mq_consume_log;
TRUNCATE TABLE course_stock;
TRUNCATE TABLE course;
TRUNCATE TABLE student;

-- -------------------------------------------------------------------
-- 1 万学生
-- -------------------------------------------------------------------
-- 用递归 CTE 生成序列。注意 cte_max_recursion_depth 默认只有 1000，
-- 生成 1 万条必须调大，否则会在第 1001 条静默截断——
-- 表现是"脚本跑完了但只有 1000 条数据"，非常容易误判为造数据失败。
SET SESSION cte_max_recursion_depth = 20000;

INSERT INTO student (student_no, name, password, status)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 10000
)
SELECT
    CONCAT('S', LPAD(n, 9, '0')),
    CONCAT('学生', n),
    @pwd_hash,
    1
FROM seq;

-- -------------------------------------------------------------------
-- 100 门课 + 对应库存
-- -------------------------------------------------------------------
-- 选课时间窗口设成"一天前 ~ 一年后"，这样造完数据随时可以压测，
-- 不用等到某个时间点。
INSERT INTO course (course_name, teacher, credit, start_time, end_time)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
SELECT
    CONCAT('课程', n),
    CONCAT('教师', n),
    CAST(2.0 + (n % 5) * 0.5 AS DECIMAL(3, 1)),
    DATE_SUB(NOW(), INTERVAL 1 DAY),
    DATE_ADD(NOW(), INTERVAL 1 YEAR)
FROM seq;

-- 每门课 100 个名额
INSERT INTO course_stock (course_id, capacity, stock, sold, version)
SELECT id, 100, 100, 0, 0 FROM course;

-- -------------------------------------------------------------------
-- 验证
-- -------------------------------------------------------------------
SELECT '学生数' AS item, COUNT(*) AS cnt FROM student
UNION ALL SELECT '课程数', COUNT(*) FROM course
UNION ALL SELECT '库存行数', COUNT(*) FROM course_stock
UNION ALL SELECT '总名额', SUM(capacity) FROM course_stock
UNION ALL SELECT '剩余名额', SUM(stock) FROM course_stock;
