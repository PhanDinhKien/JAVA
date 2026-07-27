package com.microservice.orderservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình RabbitMQ cho Order Service (Publisher)
 *
 * Kiến trúc:
 * Exchange (order.exchange) → Binding (order.created) → Queue (order.created.queue)
 *                                                    → Queue (order.created.dlq)  [Dead Letter]
 */
@Configuration
public class RabbitMQConfig {

    // Exchange
    public static final String ORDER_EXCHANGE = "order.exchange";

    // Queue & Routing Key
    public static final String ORDER_CREATED_QUEUE = "order.created.queue";
    public static final String ORDER_CREATED_ROUTING_KEY = "order.created";

    // Dead Letter Queue (cho message xử lý thất bại)
    public static final String ORDER_CREATED_DLQ = "order.created.dlq";
    public static final String ORDER_DLX = "order.dlx";

    /**
     * Topic Exchange — cho phép routing linh hoạt theo pattern
     * VD: order.created, order.updated, order.cancelled
     */
    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(ORDER_EXCHANGE);
    }

    /**
     * Dead Letter Exchange — nhận message bị reject sau N lần retry
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(ORDER_DLX);
    }

    /**
     * Main Queue — consumer đọc từ đây
     * Khi message bị reject → chuyển sang DLQ
     */
    @Bean
    public Queue orderCreatedQueue() {
        return QueueBuilder.durable(ORDER_CREATED_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_DLX)
                .withArgument("x-dead-letter-routing-key", ORDER_CREATED_DLQ)
                .build();
    }

    /**
     * Dead Letter Queue — chứa message xử lý thất bại
     * Có thể manual retry hoặc phân tích lỗi sau
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(ORDER_CREATED_DLQ).build();
    }

    /**
     * Binding: Exchange → Queue (theo routing key)
     */
    @Bean
    public Binding orderCreatedBinding() {
        return BindingBuilder
                .bind(orderCreatedQueue())
                .to(orderExchange())
                .with(ORDER_CREATED_ROUTING_KEY);
    }

    /**
     * Binding: DLX → DLQ
     */
    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder
                .bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with(ORDER_CREATED_DLQ);
    }

    /**
     * Jackson JSON converter — serialize/deserialize event thành JSON
     * (thay vì Java serialization mặc định)
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
