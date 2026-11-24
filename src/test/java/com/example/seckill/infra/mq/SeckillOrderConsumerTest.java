package com.example.seckill.infra.mq;

import com.example.seckill.entity.MqConsumeLog;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.exception.DuplicateMessageException;
import com.example.seckill.mapper.CourseStockMapper;
import com.example.seckill.mapper.MqConsumeLogMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.support.TableInfoTestSupport;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SeckillOrderConsumer 单元测试 —— 覆盖 v3 消费者的三个分支与 ack/nack 时机。
 *
 * <p>测什么：幂等闸挡下的重复消息是否<b>正常 ack 而不是进死信</b>、库存不足是否<b>标记失败并正常 ack</b>、
 * 订单已被改过是否<b>归还库存</b>、技术异常是否<b>nack 且不重投</b>。
 *
 * <p>不测什么：真实的 ack/nack 是否被 broker 收到（这要真 RabbitMQ，见 docs/阶段5-任务书-v3-mq-async.md
 * 的十四项自测）、事务是否真的回滚（这要真数据库，Mockito 里事务边界是假的）。
 *
 * <p>🔴 关于 TransactionTemplate 的打桩：真实环境它负责开事务/提交/回滚，
 * 这里用 {@code doAnswer} 让 {@code executeWithoutResult} <b>直接同步执行回调</b>，
 * 这样被测代码里的"事务内逻辑"能跑起来，但我们只验证"逻辑执行了什么"，
 * <b>验证不了"事务是否回滚"</b> —— 测试报告里写清了这条边界。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)   // 各分支打的桩不同，不要求每个都用到
class SeckillOrderConsumerTest {

    @Mock
    private MqConsumeLogMapper mqConsumeLogMapper;

    @Mock
    private SeckillOrderMapper seckillOrderMapper;

    @Mock
    private CourseStockMapper courseStockMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private Channel channel;

    @Mock
    private Message rawMessage;

    private SeckillOrderConsumer consumer;

    private static final long DELIVERY_TAG = 7L;

    @BeforeAll
    static void initTableInfo() {
        // 消费者里没用 LambdaQueryWrapper，但 MqConsumeLog 的 insert/updateById 在真实环境会走
        // TableInfo；为了和其它测试类保持一致、也为了将来改代码时不炸，这里统一预热。
        TableInfoTestSupport.initAll();
    }

    @BeforeEach
    void setUp() throws Exception {
        consumer = new SeckillOrderConsumer(
                mqConsumeLogMapper, seckillOrderMapper, courseStockMapper, transactionTemplate);

        // 让 TransactionTemplate 同步执行回调（见类注释：这只能测逻辑，测不了回滚）
        //
        // 🔴 参数类型必须是 Consumer 不是 TransactionCallback：
        //    executeWithoutResult 的形参是 java.util.function.Consumer<TransactionStatus>，
        //    execute 的形参才是 TransactionCallback。强转成 TransactionCallback 会在
        //    运行期抛 ClassCastException —— 而这个异常被被测代码的 catch(Exception) 吞掉，
        //    表现成"所有用例都走了技术异常分支（basicNack）"，非常难看出真实原因。
        doAnswer(inv -> {
            java.util.function.Consumer<?> callback = inv.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        // ack/nack 需要拿到 deliveryTag
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(DELIVERY_TAG);
        when(rawMessage.getMessageProperties()).thenReturn(props);
    }

    private static SeckillMessage msg() {
        // msgId 用 UUID 是设计约定（不能用 orderNo：退课后重选会复用同一 orderNo 但产生新消息）
        return new SeckillMessage("msg-uuid-0001", "ORD-0001", 1L, 88L);
    }

    // ==================== 分支 1：正常落单 ====================

    @Test
    @DisplayName("正常消息 → 扣库存、改订单成功、日志 status=1、basicAck")
    void onMessage_normal_decrementsAndAcks() throws Exception {
        when(courseStockMapper.tryDecrement(88L)).thenReturn(1);
        when(seckillOrderMapper.updateStatusByOrderNo("ORD-0001", SeckillOrder.STATUS_SUCCESS)).thenReturn(1);

        consumer.onMessage(msg(), rawMessage, channel);

        // ① 幂等闸先插了一行日志，msgId 必须是消息里那个
        ArgumentCaptor<MqConsumeLog> inserted = ArgumentCaptor.forClass(MqConsumeLog.class);
        verify(mqConsumeLogMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getMsgId()).isEqualTo("msg-uuid-0001");
        // ⚠️ 这里不断言 insert 时的 status == 0：
        //    Mockito 的 ArgumentCaptor 保存的是<b>对象引用</b>而不是快照，
        //    而被测代码后面会在这个同一个对象上 setStatus(1)，
        //    所以事后读到的永远是最终值 1。这不是被测代码的 bug，是断言写法的陷阱。
        //    "插入时是 0" 这一点由下面 updateById 的 status==1 间接证明（0 → 1 的迁移）。

        // ② 扣库存 + 改订单
        verify(courseStockMapper).tryDecrement(88L);
        verify(seckillOrderMapper).updateStatusByOrderNo("ORD-0001", SeckillOrder.STATUS_SUCCESS);

        // ③ 日志收尾为 status=1（成功）
        ArgumentCaptor<MqConsumeLog> updated = ArgumentCaptor.forClass(MqConsumeLog.class);
        verify(mqConsumeLogMapper).updateById(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo(1);

        // ④ 必须 ack，且不能 nack
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    @DisplayName("ack 必须在事务回调执行之后（先提交再 ack，否则消息丢了订单没落）")
    void onMessage_acksAfterTransactionRuns() throws Exception {
        when(courseStockMapper.tryDecrement(88L)).thenReturn(1);
        when(seckillOrderMapper.updateStatusByOrderNo(anyString(), anyInt())).thenReturn(1);

        consumer.onMessage(msg(), rawMessage, channel);

        // 🔴 顺序错了会怎样：如果先 ack 再执行事务，ack 之后进程崩了，
        //    消息已确认不会重投，但订单永远停在 0 —— "订单少了但没有任何异常日志"。
        InOrder order = inOrder(transactionTemplate, channel);
        order.verify(transactionTemplate).executeWithoutResult(any());
        order.verify(channel).basicAck(DELIVERY_TAG, false);
    }

    // ==================== 分支 2：重复消息 ====================

    @Test
    @DisplayName("重复消息（幂等闸撞唯一索引）→ 正常 ack，不进死信，也不碰库存")
    void onMessage_duplicate_acksWithoutTouchingStock() throws Exception {
        when(mqConsumeLogMapper.insert(any(MqConsumeLog.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_msg_id'"));

        consumer.onMessage(msg(), rawMessage, channel);

        // 🔴 这是最容易被写错的一条：重复消息是"已经处理过了"，不是故障。
        //    如果这里 nack(requeue=false)，消息会进死信 —— 运维看到一堆死信以为系统坏了。
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(DELIVERY_TAG, false, false);

        // 而且绝不能扣第二次库存
        verify(courseStockMapper, never()).tryDecrement(any());
        verify(seckillOrderMapper, never()).updateStatusByOrderNo(anyString(), anyInt());
    }

    // ==================== 分支 3：库存不足（业务失败） ====================

    @Test
    @DisplayName("库存不足 → 订单标 status=2 + 原因、日志 status=2、正常 ack（不进死信）")
    void onMessage_stockEmpty_marksFailedAndAcks() throws Exception {
        when(courseStockMapper.tryDecrement(88L)).thenReturn(0);   // affected=0 表示没扣到

        consumer.onMessage(msg(), rawMessage, channel);

        verify(seckillOrderMapper).updateStatusAndFailReason(
                "ORD-0001", SeckillOrder.STATUS_FAILED, "库存不足");

        ArgumentCaptor<MqConsumeLog> updated = ArgumentCaptor.forClass(MqConsumeLog.class);
        verify(mqConsumeLogMapper).updateById(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo(2);
        assertThat(updated.getValue().getErrorMsg()).isEqualTo("库存不足");

        // 🔴 业务失败要正常 ack，不能 nack：
        //    重投一万次结果都一样（库存不会自己长回来），只会刷爆死信队列。
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(DELIVERY_TAG, false, false);

        // 业务失败路径不该去改订单成功状态
        verify(seckillOrderMapper, never()).updateStatusByOrderNo(anyString(), anyInt());
    }

    // ==================== 分支 4：订单状态已被改过 ====================

    @Test
    @DisplayName("订单已被改（用户秒退课）→ 归还库存，不让名额凭空消失")
    void onMessage_orderAlreadyChanged_returnsStock() throws Exception {
        when(courseStockMapper.tryDecrement(88L)).thenReturn(1);
        // rows=0：说明这条订单已经不是 status=0 了（被退课/被别的消息处理过）
        when(seckillOrderMapper.updateStatusByOrderNo("ORD-0001", SeckillOrder.STATUS_SUCCESS)).thenReturn(0);

        consumer.onMessage(msg(), rawMessage, channel);

        // 🔴 不归还的话：库存被扣了、订单却没变成成功 —— 这个名额永久消失。
        //    表现是"不知不觉少卖了几个"，查账时只会看到 stock+sold 不守恒。
        verify(courseStockMapper).returnStock(88L);

        // 依然是成功路径，正常 ack
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    // ==================== 分支 5：技术异常 ====================

    @Test
    @DisplayName("技术异常（如数据库连不上）→ basicNack 且 requeue=false（进死信，不无限重投）")
    void onMessage_technicalError_nacksWithoutRequeue() throws Exception {
        when(courseStockMapper.tryDecrement(88L)).thenThrow(new RuntimeException("数据库连接断了"));

        consumer.onMessage(msg(), rawMessage, channel);

        // 🔴 第三个参数 requeue 必须是 false。
        //    写 true 的话：坏消息被反复投回来，消费者空转，队列深度永远降不下去，
        //    这种"消息风暴"比直接失败更难查（CPU 打满但没有一条 ERROR 说清楚原因）。
        verify(channel).basicNack(DELIVERY_TAG, false, false);

        // 绝不能既 nack 又 ack
        verify(channel, never()).basicAck(any(Long.class), any(Boolean.class));
    }

    @Test
    @DisplayName("技术异常时不能把订单标成失败（那会掩盖真正的故障）")
    void onMessage_technicalError_doesNotMarkOrderFailed() throws Exception {
        when(courseStockMapper.tryDecrement(88L)).thenThrow(new RuntimeException("数据库连接断了"));

        consumer.onMessage(msg(), rawMessage, channel);

        // 数据库只是暂时连不上，订单还有救（进死信后可以人工重放）。
        // 如果这里标成 status=2「库存不足」，就是把基础设施故障写成了业务结论。
        verify(seckillOrderMapper, never()).updateStatusAndFailReason(anyString(), anyInt(), anyString());
    }

    // ==================== 幂等闸必须与业务同事务 ====================

    @Test
    @DisplayName("幂等日志的 insert 发生在事务回调内部（不在外面）")
    void onMessage_idempotencyGateRunsInsideTransaction() throws Exception {
        when(courseStockMapper.tryDecrement(88L)).thenReturn(1);
        when(seckillOrderMapper.updateStatusByOrderNo(anyString(), anyInt())).thenReturn(1);

        consumer.onMessage(msg(), rawMessage, channel);

        InOrder order = inOrder(transactionTemplate, mqConsumeLogMapper);
        order.verify(transactionTemplate).executeWithoutResult(any());
        order.verify(mqConsumeLogMapper).insert(any(MqConsumeLog.class));
    }

    @Test
    @DisplayName("幂等闸在扣库存之前执行（顺序反了会先扣库存再发现是重复消息）")
    void onMessage_idempotencyGateRunsBeforeStockDecrement() throws Exception {
        when(courseStockMapper.tryDecrement(88L)).thenReturn(1);
        when(seckillOrderMapper.updateStatusByOrderNo(anyString(), anyInt())).thenReturn(1);

        consumer.onMessage(msg(), rawMessage, channel);

        InOrder order = inOrder(mqConsumeLogMapper, courseStockMapper);
        order.verify(mqConsumeLogMapper).insert(any(MqConsumeLog.class));
        order.verify(courseStockMapper).tryDecrement(88L);
    }

    // ==================== 消息体字段必须被正确传递 ====================

    @Test
    @DisplayName("用的是消息里的 courseId 和 orderNo，不是写死的常量")
    void onMessage_usesMessageFields() throws Exception {
        SeckillMessage other = new SeckillMessage("msg-uuid-9999", "ORD-9999", 42L, 17L);
        when(courseStockMapper.tryDecrement(17L)).thenReturn(1);
        when(seckillOrderMapper.updateStatusByOrderNo("ORD-9999", SeckillOrder.STATUS_SUCCESS)).thenReturn(1);

        consumer.onMessage(other, rawMessage, channel);

        verify(courseStockMapper).tryDecrement(17L);
        verify(seckillOrderMapper).updateStatusByOrderNo("ORD-9999", SeckillOrder.STATUS_SUCCESS);
        // 14. 防止有人把 studentId 拼进 orderNo 之类的低级错
        verify(courseStockMapper, never()).tryDecrement(88L);
    }
}
