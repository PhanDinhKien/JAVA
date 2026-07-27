package com.microservice.userservice.event;

import com.microservice.userservice.config.RabbitMQConfig;
import com.microservice.userservice.service.OrderStatisticService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumer — nhận OrderCreatedEvent từ RabbitMQ
 *
 * Luồng: Order-service publish event
 *   → RabbitMQ queue "order.created.queue"
 *   → @RabbitListener nhận event
 *   → OrderStatisticService.updateStatistic()
 *
 * Xử lý lỗi:
 *   - Lỗi tạm thời (DB timeout) → throw exception → RabbitMQ retry
 *   - Sau N lần retry → reject → Dead Letter Queue (DLQ)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventConsumer {

    private final OrderStatisticService orderStatisticService;

    @RabbitListener(queues = RabbitMQConfig.ORDER_CREATED_QUEUE)
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("📥 Received OrderCreatedEvent: orderId={}, userId={}, product={}, totalPrice={}",
                event.getOrderId(), event.getUserId(), event.getProductName(), event.getTotalPrice());

        try {
            orderStatisticService.updateStatistic(event);
            log.info("✅ Successfully processed OrderCreatedEvent for orderId={}", event.getOrderId());
        } catch (Exception e) {
            log.error("❌ Failed to process OrderCreatedEvent for orderId={}: {}",
                    event.getOrderId(), e.getMessage());
            // Re-throw để RabbitMQ retry hoặc chuyển vào DLQ
            throw e;
        }
    }
}
