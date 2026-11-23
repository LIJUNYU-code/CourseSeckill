package com.example.seckill.infra.mq;


import com.example.seckill.config.RabbitConfig;
import com.example.seckill.entity.MqConsumeLog;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.exception.DuplicateMessageException;
import com.example.seckill.mapper.CourseStockMapper;
import com.example.seckill.mapper.MqConsumeLogMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderConsumer {

    private final MqConsumeLogMapper mqConsumeLogMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final CourseStockMapper courseStockMapper;
    private final TransactionTemplate transactionTemplate;


    @RabbitListener(queues = RabbitConfig.ORDER_QUEUE)
    public void onMessage(SeckillMessage message, Message raw, Channel channel) throws IOException {
        long deliveryTag = raw.getMessageProperties().getDeliveryTag();
        log.info("收到消息 msgId={} orderNo={}", message.msgId(), message.orderNo());
        try {
            handle(message);
            channel.basicAck(deliveryTag, false);
        } catch (DuplicateMessageException e) {
            // 幂等闸挡下的重复消息：不是错误，正常 ack
            log.warn("重复消息，已忽略 msgId={} orderNo={}", message.msgId(), message.orderNo());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            //技术异常：不重投，进死信
            log.error("消费失败，进死信 msgId={} orderNo={}", message.msgId(), message.orderNo(), e);
            channel.basicNack(deliveryTag, false, false);
        }

    }

    private void handle(SeckillMessage message) {
        transactionTemplate.executeWithoutResult(status -> {
            // ① 幂等闸
            MqConsumeLog consumeLog = MqConsumeLog.builder()
                    .msgId(message.msgId())
                    .status(0)
                    .build();
            try {
                mqConsumeLogMapper.insert(consumeLog);
            } catch (DuplicateKeyException e) {
                throw new DuplicateMessageException("重复消息");   // 跳出事务
            }
            // ② 扣库存
            int affected = courseStockMapper.tryDecrement(message.courseId());
            if (affected == 0) {
                seckillOrderMapper.updateStatusAndFailReason(
                        message.orderNo(), SeckillOrder.STATUS_FAILED, "库存不足");
                consumeLog.setStatus(2);
                consumeLog.setErrorMsg("库存不足");
                mqConsumeLogMapper.updateById(consumeLog);
                return;    // 业务失败，正常返回（事务提交，消息 ack）
            }

            // ③ 改订单
            int rows = seckillOrderMapper.updateStatusByOrderNo(
                    message.orderNo(), SeckillOrder.STATUS_SUCCESS);
            if (rows == 0) {
                log.warn("订单状态已更改，跳过 orderNo={}", message.orderNo());
                courseStockMapper.returnStock(message.courseId());
            }

            // ④ 更新日志
            consumeLog.setStatus(1);
            mqConsumeLogMapper.updateById(consumeLog);

        });
    }
}
