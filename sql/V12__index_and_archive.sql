-- ===================================================================
-- V12：补两个缺失的索引（阶段 9 多实例正确性）
-- ===================================================================
-- 这两个索引都不是"多实例"带来的，而是顺着多实例排查时暴露出来的既有问题：
--
--   1. mq_consume_log 没有任何时间索引
--      → 想按时间清理/归档这张表时只能全表扫。它是一张"每消费一条消息加一行"的表，
--        长期运行必然无限增长。
--
--   2. seckill_order 没有 (status, create_time) 组合索引
--      → PendingOrderSweeper 每分钟都要跑一次
--           WHERE status = 0 AND create_time < ? AND update_time = create_time
--           ORDER BY id LIMIT 200
--        实测 EXPLAIN 显示 possible_keys = NULL、key = PRIMARY、type = index，
--        也就是退化成"按主键顺序全表扫、逐行判断 status"。表小的时候是毫秒级，
--        但 PENDING 行很少时会白白扫过大量历史订单。
--
-- 执行方式：
--   mysql -uroot -p --default-character-set=utf8mb4 -e "source D:/software/seckill-course/sql/V12__index_and_archive.sql"
--   （路径用正斜杠）
-- ===================================================================

USE seckill;

-- -------------------------------------------------------------------
-- 1. mq_consume_log：时间索引
-- -------------------------------------------------------------------
-- 加之前先看现在的索引，确认没有重复：
--   SHOW INDEX FROM mq_consume_log;
ALTER TABLE mq_consume_log
    ADD KEY idx_create_time (create_time);

-- 归档/清理策略（二选一，本项目建议"归档"）：
--
--   方案 A 直接删（简单，但删了就查不到历史）：
--     DELETE FROM mq_consume_log WHERE create_time < NOW() - INTERVAL 7 DAY LIMIT 1000;
--     -- 注意 LIMIT：一次删几百万行会长时间锁表、撑爆 undo log。
--     -- 要循环执行直到 affected rows = 0。
--
--   方案 B 先归档再删（可追溯，本项目采用这个思路）：
--     CREATE TABLE mq_consume_log_archive LIKE mq_consume_log;
--     INSERT INTO mq_consume_log_archive
--       SELECT * FROM mq_consume_log WHERE create_time < NOW() - INTERVAL 7 DAY LIMIT 1000;
--     DELETE FROM mq_consume_log WHERE create_time < NOW() - INTERVAL 7 DAY LIMIT 1000;
--
-- 本脚本只建索引，不建归档表也不删数据 —— 归档是运维动作，应该由人决定什么时候跑。

-- -------------------------------------------------------------------
-- 2. seckill_order：(status, create_time) 组合索引
-- -------------------------------------------------------------------
-- 为什么是这个顺序（status 在前）：
--   查询条件是 status = 0 AND create_time < ?。等值条件放左边、范围条件放右边，
--   这样索引能先定位到 status=0 的那一段，再在这段里按 create_time 做范围扫描。
--   反过来 (create_time, status) 也能用，但范围列在前会让 status 的过滤退化成
--   "在索引里逐条判断"，效果差一些。
--
-- 为什么不是 (status, create_time, update_time)：
--   update_time = create_time 是两个列之间的比较，不是常量条件，索引帮不上忙
--   （MySQL 8.0 没有"列间比较"的索引下推）。把它加进索引只会让索引变大、写变慢。
ALTER TABLE seckill_order
    ADD KEY idx_status_create (status, create_time);

-- 验证：加完之后再跑一次 EXPLAIN，期望 type 从 index 变成 range、
-- key 从 PRIMARY 变成 idx_status_create。
--
--   EXPLAIN SELECT * FROM seckill_order
--   WHERE status = 0 AND create_time < NOW() - INTERVAL 5 MINUTE
--     AND update_time = create_time
--   ORDER BY id LIMIT 200;
--
-- 注意：表里如果一行数据都没有（刚建库），MySQL 可能仍然选 PRIMARY，
-- 因为优化器认为扫全表更便宜。这是正常的，有数据之后才会走新索引。
