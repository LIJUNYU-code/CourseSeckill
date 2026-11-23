package com.example.seckill.infra.mq;

import com.example.seckill.common.ResultCode;
import com.example.seckill.config.RabbitConfig;
import com.example.seckill.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillMessageProducer {

    private static final long CONFIRM_TIMEOUT_SECONDS = 3L;

    private final RabbitTemplate rabbitTemplate;


    /**
     * 发送秒杀消息并等待 broker confirm。
     *
     * @return true = 已到 broker；false = nack（broker 明确拒收）
     * @throws BusinessException 超时（不确定状态，调用方需回滚）
     */
    public boolean send(SeckillMessage message) {
        CorrelationData correlationData = new CorrelationData(message.orderNo());
        rabbitTemplate.convertAndSend(
                RabbitConfig.ORDER_EXCHANGE,
                RabbitConfig.ORDER_ROUTING_KEY,
                message,
                correlationData);
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (confirm.isAck()) {
                log.info("消息已确认 order={} msgId={}", message.orderNo(), message.msgId());
                return true;
            }
            log.error("broker拒收 order={} reason={}", message.orderNo(), confirm.getReason());
            return false;
        } catch (TimeoutException e) {
            log.error("等待 confirm 超时 roderNo={} msgId={}", message.orderNo(), message.msgId());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "系统繁忙，请重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "系统繁忙，请重试");
        } catch (ExecutionException e) {
            log.error("confirm 异常 orderNo={}", message.orderNo(), e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "系统繁忙，请重试");
        }
    }


}