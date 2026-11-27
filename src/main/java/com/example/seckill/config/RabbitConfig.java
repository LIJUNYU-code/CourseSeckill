package com.example.seckill.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "seckill.order.exchange";
    public static final String ORDER_QUEUE = "seckill.order.queue";
    public static final String ORDER_ROUTING_KEY = "seckill.order";
    public static final String DLX_EXCHANGE = "seckill.dlx.exchange";
    public static final String DLQ_QUEUE = "seckill.order.dlq";
    public static final String DLQ_ROUTING_KEY = "seckill.order.dlq";

    @Bean
    DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE);  // ORDER_EXCHANGE，durable=true，autoDelete=false
    }

    @Bean
    DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE);    // DLX_EXCHANGE
    }

    @Bean
    Queue orderQueue() {
        return QueueBuilder.durable(ORDER_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue dlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }            // DLQ_QUEUE，durable=true

    @Bean
    Binding orderBinding() {
        return BindingBuilder.bind(orderQueue()).to(orderExchange()).with(ORDER_ROUTING_KEY);
    }

    // orderExchange → orderQueue，routing key = ORDER_ROUTING_KEY
    @Bean
    Binding dlqBinding() {
        return BindingBuilder.bind(dlqQueue()).to(dlxExchange()).with(DLQ_ROUTING_KEY);
    }            // dlxExchange → dlqQueue，routing key = DLQ_ROUTING_KEY

    @Bean
    MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    } // JacksonJsonMessageConverter

    /**
     * 死信队列专用监听容器工厂：<b>换掉消息转换器</b>。
     *
     * <p>🔴 为什么必须单独给死信队列配一个工厂（这是实测踩出来的）：
     *
     * <p>直觉上"方法签名收 {@code org.springframework.amqp.core.Message} 就不会走转换器"，
     * <b>这个直觉是错的</b>。看 Spring AMQP 4.1.1 的源码，
     * {@code MessagingMessageListenerAdapter.onMessage()} 第 183 行
     * <b>无条件</b>调用 {@code toMessagingMessage(amqpMessage)}，
     * 它内部又无条件调 {@code MessagingMessageConverter.fromMessage()} →
     * {@code extractPayload()} → 配置的 {@code MessageConverter}。
     * 也就是说：<b>不管你的方法签名收什么类型，payload 转换都会先跑一遍。</b>
     *
     * <p>而业务队列用的 {@code JacksonJsonMessageConverter} 遇到坏 JSON 会抛
     * {@code MessageConversionException}。死信队列里躺着的<b>恰恰就是</b>转不过去的坏消息 ——
     * 结果就是：坏消息从业务队列"成功"进了死信队列，却在死信队列上再挂一次，
     * 被 {@code ConditionalRejectingErrorHandler} 判定为不可恢复错误直接丢弃，
     * <b>证据彻底消失，死信消费者一行日志都没打出来</b>（实测现象）。
     *
     * <p>解法：死信消费者根本不需要反序列化成对象，它只做"把原始字节落库"。
     * 所以给死信队列换成 {@code SimpleMessageConverter} —— 它只在
     * {@code contentType} 是 {@code text/*} 时才解码成 String，其它情况一律返回
     * <b>原始 {@code byte[]}</b>，永远不会因为内容不合法而抛异常。
     */
    @Bean
    SimpleRabbitListenerContainerFactory deadLetterContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        // 先让 Boot 把 application.yaml 里的 listener 配置（manual ack / concurrency / prefetch）套上
        configurer.configure(factory, connectionFactory);
        // 再覆盖转换器：这是本工厂存在的唯一理由
        factory.setMessageConverter(new SimpleMessageConverter());
        return factory;
    }
}
