package com.example.seckill.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
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
}
