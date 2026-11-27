package com.example.seckill.infra.mq;

import com.example.seckill.config.RabbitConfig;
import com.example.seckill.entity.MqDeadLetter;
import com.example.seckill.mapper.MqDeadLetterMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterConsumer {

    private static final int BODY_MAX = 1000;

    private final MqDeadLetterMapper mqDeadLetterMapper;
    private final ObjectMapper objectMapper;

    // 🔴 containerFactory 不能省：默认工厂用的是 JacksonJsonMessageConverter，
    //    坏 JSON 会在转换阶段就抛 MessageConversionException，
    //    消息被判定为不可恢复直接丢弃，这个类的方法根本进不来。
    //    专用工厂（RabbitConfig.deadLetterContainerFactory）换成 SimpleMessageConverter，
    //    只返回原始 byte[]，永不因内容不合法抛异常。
    @RabbitListener(queues = RabbitConfig.DLQ_QUEUE,
            containerFactory = "deadLetterContainerFactory")
    public void onMessage(Message raw, Channel channel) throws IOException {
        long deliveryTag = raw.getMessageProperties().getDeliveryTag();
        String body = new String(raw.getBody(), StandardCharsets.UTF_8);

        MqDeadLetter record = parse(raw, body);
        log.error("收到死信 reason={} orderNo={} msgId={} body={}", record.getReason(), record.getOrderNo(),  record.getMsgId(), body);
        try {
            mqDeadLetterMapper.insert(record);
        }catch (Exception e){
            // 落库失败也要 ack：requeue 会让 broker 每 0.1 秒重投一次，
            // 数据库挂着的时候直接把 CPU 和日志打爆，而且永远出不来。
            // 证据已经在上面那行 log.error 里（含完整 body）。
            log.error("死信落库失败，原始消息 body={}", body, e);
        }
        channel.basicAck(deliveryTag, false);
    }

    /** 解析 body + 读 x-death 头。任何一步失败都降级成 null，绝不抛。 */
    private MqDeadLetter parse(Message raw, String body){
        MqDeadLetter.MqDeadLetterBuilder b = MqDeadLetter.builder()
                .reason(extractReason(raw))
                .body(body.length() > BODY_MAX ? body.substring(0, BODY_MAX) : body);
        try {
            SeckillMessage m = objectMapper.readValue(body, SeckillMessage.class);
            b.msgId(m.msgId()).orderNo(m.orderNo())
                    .studentId(m.studentId()).courseId(m.courseId());
        } catch (Exception e) {
            // 坏 JSON 是死信的常见形态，不是异常情况，不需要打堆栈。
            log.debug("死信 body 不是合法 SeckillMessage，只存原始内容");
        }
        return b.build();
    }

    /**
     * 从 x-death 头取死信原因。
     *
     * <p>结构是 List&lt;Map&gt;，第一项是最近一次死信：
     * {@code {reason=rejected, queue=seckill.order.queue, count=1, routing-keys=[seckill.order]}}。
     * 没有这个头（比如消息是被直接 publish 到 DLQ 的）就返回 unknown。
     */
    private String extractReason(Message raw) {
        List<Map<String, ?>> xDeath = raw.getMessageProperties().getXDeathHeader();
        if (xDeath == null || xDeath.isEmpty()) {
            return "unknown";
        }
        Object reason = xDeath.get(0).get("reason");
        return reason == null ? "unknown" : String.valueOf(reason);
    }
}

