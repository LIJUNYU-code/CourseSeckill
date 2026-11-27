-- ============================================================
-- V11 死信消息表（阶段 8）
-- ============================================================
-- 背景：
--   业务队列 seckill.order.queue 的死信会进 seckill.order.dlq，
--   在此之前 DLQ 没有任何消费者，消息只堆积不落库，
--   出事时只能靠 rabbitmqctl 或管理台手工看，重启就没了。
--
-- 与 mq_consume_log 的区别：
--   mq_consume_log 是「消费成功 / 业务失败」的账，有 UNIQUE(msg_id)，一条消息一行；
--   mq_dead_letter 是「技术异常 / 坏消息」的账，同一条消息可能因不同原因死多次，
--   故意不加唯一约束。
-- ============================================================

USE seckill;

CREATE TABLE IF NOT EXISTS mq_dead_letter (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    msg_id      VARCHAR(64)           DEFAULT NULL   COMMENT '消息唯一标识（坏 JSON 时为 NULL）',
    order_no    VARCHAR(40)           DEFAULT NULL   COMMENT '订单号（解析不出时为 NULL）',
    student_id  BIGINT                DEFAULT NULL   COMMENT '学生 ID',
    course_id   BIGINT                DEFAULT NULL   COMMENT '课程 ID',
    reason      VARCHAR(50)  NOT NULL                COMMENT '死信原因：rejected/expired/maxlen/unknown',
    body        VARCHAR(1000)         DEFAULT NULL   COMMENT '原始消息体（截断到 1000 字符）',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_create_time (create_time),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '死信消息（MQ 技术异常的现场）';
