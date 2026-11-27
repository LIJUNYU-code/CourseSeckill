package com.example.seckill.infra.mq;

import com.example.seckill.config.RabbitConfig;
import com.example.seckill.entity.MqDeadLetter;
import com.example.seckill.mapper.MqDeadLetterMapper;
import com.example.seckill.support.TableInfoTestSupport;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DeadLetterConsumer} 的单元测试。
 *
 * <p>这个类的职责只有三件事：<b>解析 → 落库 → ack</b>。
 * 所以测试的重点不是"解析得对不对"，而是<b>出错时还 ack 不 ack</b>：
 * 死信队列的消费一旦卡住，消息会无限重投，把 CPU 和日志打爆。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeadLetterConsumerTest {

    private static final long TAG = 9L;

    /** body 截断长度，与实现里的 BODY_MAX 一致。 */
    private static final int BODY_MAX = 1000;

    @Mock
    private MqDeadLetterMapper mqDeadLetterMapper;

    @Mock
    private Channel channel;

    @Mock
    private Message rawMessage;

    private DeadLetterConsumer consumer;

    private MessageProperties props;

    @BeforeAll
    static void initTableInfo() {
        // MqDeadLetter 的 @TableName / @TableId 要被 MyBatis-Plus 认识，
        // 否则 insert 时生成 SQL 会抛 can not find lambda cache
        TableInfoTestSupport.initAll();
    }

    @BeforeEach
    void setUp() {
        consumer = new DeadLetterConsumer(mqDeadLetterMapper, new ObjectMapper());
        props = new MessageProperties();
        props.setDeliveryTag(TAG);
        when(rawMessage.getMessageProperties()).thenReturn(props);
    }

    /** 造一条合法死信：body 是 SeckillMessage 的 JSON，x-death 头写 rejected。 */
    private void legalMessage() {
        setBody("{\"msgId\":\"m-1\",\"orderNo\":\"O-1\",\"studentId\":7,\"courseId\":88}");
        Map<String, Object> death = new HashMap<>();
        death.put("reason", "rejected");
        death.put("queue", RabbitConfig.ORDER_QUEUE);
        death.put("count", 1L);
        props.setHeader("x-death", List.of(death));
    }

    private void setBody(String json) {
        when(rawMessage.getBody()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
    }

    /** 取出真正传给 mapper 的那条记录。 */
    private MqDeadLetter captured() {
        ArgumentCaptor<MqDeadLetter> captor = ArgumentCaptor.forClass(MqDeadLetter.class);
        verify(mqDeadLetterMapper, times(1)).insert(captor.capture());
        return captor.getValue();
    }

    // ---------- 用例 1：正常死信 ----------

    @Test
    @DisplayName("用例1 正常死信：四个业务字段都解析出来，reason 来自 x-death，并 ack")
    void legalDeadLetter_parsesFieldsAndAcks() throws Exception {
        legalMessage();

        consumer.onMessage(rawMessage, channel);

        MqDeadLetter saved = captured();
        assertThat(saved.getMsgId()).isEqualTo("m-1");
        assertThat(saved.getOrderNo()).isEqualTo("O-1");
        assertThat(saved.getStudentId()).isEqualTo(7L);
        assertThat(saved.getCourseId()).isEqualTo(88L);
        assertThat(saved.getReason()).isEqualTo("rejected");
        assertThat(saved.getBody()).contains("\"orderNo\":\"O-1\"");

        verify(channel, times(1)).basicAck(TAG, false);
    }

    // ---------- 用例 2：坏 JSON ----------

    @Test
    @DisplayName("用例2 坏 JSON：仍然落库（msgId 为 null，body 保留原文），并 ack")
    void brokenJson_stillPersistsAndAcks() throws Exception {
        // 死信里躺着的恰恰经常是转不过去的坏 JSON，不能因为解析失败就丢掉证据
        setBody("{\"msgId\":\"dlq-test-bad\",\"orderNo\":");

        consumer.onMessage(rawMessage, channel);

        MqDeadLetter saved = captured();
        assertThat(saved.getMsgId()).isNull();
        assertThat(saved.getOrderNo()).isNull();
        assertThat(saved.getBody()).isEqualTo("{\"msgId\":\"dlq-test-bad\",\"orderNo\":");
        assertThat(saved.getReason()).isEqualTo("unknown");

        verify(channel, times(1)).basicAck(TAG, false);
    }

    // ---------- 用例 3：没有 x-death 头 ----------

    @Test
    @DisplayName("用例3 没有 x-death 头：reason 降级成 unknown，仍落库")
    void noXDeathHeader_reasonIsUnknown() throws Exception {
        setBody("{\"msgId\":\"m-3\",\"orderNo\":\"O-3\",\"studentId\":1,\"courseId\":2}");
        // 故意不设 x-death：消息是被直接 publish 到 DLQ 的时候就是这个样子

        consumer.onMessage(rawMessage, channel);

        assertThat(captured().getReason()).isEqualTo("unknown");
        verify(channel, times(1)).basicAck(TAG, false);
    }

    // ---------- 用例 4：落库失败也必须 ack（🔴 核心）----------

    @Test
    @DisplayName("用例4 落库抛异常：仍然 basicAck，且不把异常抛给监听器")
    void insertFails_stillAcksAndDoesNotRethrow() throws Exception {
        legalMessage();
        when(mqDeadLetterMapper.insert(any(MqDeadLetter.class)))
                .thenThrow(new RuntimeException("数据库连接池耗尽"));

        // 不抛异常：抛出去会被容器当成消费失败，消息重回队列
        assertThatCode(() -> consumer.onMessage(rawMessage, channel)).doesNotThrowAnyException();

        // 🔴 核心断言：落库失败也要 ack。
        // 若改成 requeue=true，broker 会每 0.1 秒重投一次，数据库挂着时直接把 CPU 打爆
        verify(channel, times(1)).basicAck(TAG, false);
    }

    // ---------- 用例 5：body 截断 ----------

    @Test
    @DisplayName("用例5 body 超过 1000 字符：落库时截断到 1000")
    void oversizedBody_isTruncated() throws Exception {
        // 前面塞一段合法 JSON 头，后面跟一坨垃圾把长度顶到 1500
        String json = "{\"msgId\":\"m-5\",\"orderNo\":\"O-5\"}" + "x".repeat(1500);
        setBody(json);

        consumer.onMessage(rawMessage, channel);

        MqDeadLetter saved = captured();
        assertThat(saved.getBody()).hasSize(BODY_MAX);
        // 截断的是尾部，开头的合法部分还在
        assertThat(saved.getBody()).startsWith("{\"msgId\":\"m-5\"");
    }

    // ---------- 用例 6：任何情况都不 nack ----------

    @Test
    @DisplayName("用例6 无论成功还是失败，都不 basicNack")
    void neverNacks() throws Exception {
        legalMessage();
        consumer.onMessage(rawMessage, channel);

        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("用例6b 落库失败时也不 basicNack")
    void neverNacksEvenWhenInsertFails() throws Exception {
        legalMessage();
        doThrow(new RuntimeException("boom")).when(mqDeadLetterMapper).insert(any(MqDeadLetter.class));

        consumer.onMessage(rawMessage, channel);

        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    // ---------- 用例 7：reason 不是硬编码 ----------

    @Test
    @DisplayName("用例7 x-death 的 reason 是 expired 时如实记录（证明不是硬编码 rejected）")
    void reasonIsReadFromHeader_notHardcoded() throws Exception {
        setBody("{\"msgId\":\"m-7\",\"orderNo\":\"O-7\"}");
        Map<String, Object> death = new HashMap<>();
        death.put("reason", "expired");
        death.put("queue", RabbitConfig.ORDER_QUEUE);
        death.put("count", 3L);
        props.setHeader("x-death", List.of(death));

        consumer.onMessage(rawMessage, channel);

        assertThat(captured().getReason()).isEqualTo("expired");
    }

    @Test
    @DisplayName("用例7b x-death 存在但 reason 字段缺失：降级成 unknown")
    void xDeathWithoutReason_isUnknown() throws Exception {
        setBody("{\"msgId\":\"m-7b\",\"orderNo\":\"O-7b\"}");
        Map<String, Object> death = new HashMap<>();
        death.put("queue", RabbitConfig.ORDER_QUEUE);
        props.setHeader("x-death", List.of(death));

        consumer.onMessage(rawMessage, channel);

        assertThat(captured().getReason()).isEqualTo("unknown");
    }
}
