package com.microservice.orderservice.event;

import com.microservice.orderservice.config.RabbitMQConfig;
import com.microservice.orderservice.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publisher — gửi OrderCreatedEvent đến RabbitMQ
 *
 * Luồng: OrderService.createOrder()
 *   → orderEventPublisher.publishOrderCreated(order)
 *   → RabbitMQ exchange "order.exchange"
 *   → routing key "order.created"
 *   → queue "order.created.queue"
 *   → User-service consumer nhận
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Publish event khi tạo order thành công
     * Non-blocking: nếu RabbitMQ chết → log warning, KHÔNG throw exception
     */
    public void publishOrderCreated(Order order) {
        try {
            OrderCreatedEvent event = OrderCreatedEvent.builder()
                    .orderId(order.getId())
                    .userId(order.getUserId())
                    .productName(order.getProductName())
                    .quantity(order.getQuantity())
                    .totalPrice(order.getTotalPrice())
                    .status(order.getStatus())
                    .createdAt(order.getCreatedAt())
                    .build();

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.ORDER_EXCHANGE,
                    RabbitMQConfig.ORDER_CREATED_ROUTING_KEY,
                    event
            );

            log.info("📤 Published OrderCreatedEvent: orderId={}, userId={}, product={}",
                    event.getOrderId(), event.getUserId(), event.getProductName());

        } catch (Exception e) {
            // RabbitMQ chết → log warning, KHÔNG ảnh hưởng order flow
            log.warn("⚠️ Failed to publish OrderCreatedEvent for orderId={}: {}",
                    order.getId(), e.getMessage());
        }
    }
}
