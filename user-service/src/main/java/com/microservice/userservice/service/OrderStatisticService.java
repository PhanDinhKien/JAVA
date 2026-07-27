package com.microservice.userservice.service;

import com.microservice.userservice.event.OrderCreatedEvent;
import com.microservice.userservice.model.OrderStatistic;
import com.microservice.userservice.repository.OrderStatisticRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Service quản lý thống kê đơn hàng của user
 *
 * Được gọi bởi OrderEventConsumer khi nhận event từ RabbitMQ
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderStatisticService {

    private final OrderStatisticRepository orderStatisticRepository;

    /**
     * Cập nhật thống kê khi có đơn hàng mới
     * - Tìm statistic theo userId → nếu chưa có → tạo mới
     * - Tăng totalOrders
     * - Cộng dồn totalSpending
     */
    @Transactional
    public void updateStatistic(OrderCreatedEvent event) {
        OrderStatistic statistic = orderStatisticRepository.findByUserId(event.getUserId())
                .orElseGet(() -> {
                    log.info("📊 Creating new OrderStatistic for userId: {}", event.getUserId());
                    return OrderStatistic.builder()
                            .userId(event.getUserId())
                            .build();
                });

        statistic.setTotalOrders(statistic.getTotalOrders() + 1);
        statistic.setTotalSpending(statistic.getTotalSpending().add(event.getTotalPrice()));
        statistic.setLastOrderAt(event.getCreatedAt());
        statistic.setLastProductName(event.getProductName());

        orderStatisticRepository.save(statistic);

        log.info("📊 Updated OrderStatistic for userId: {} → totalOrders={}, totalSpending={}",
                event.getUserId(), statistic.getTotalOrders(), statistic.getTotalSpending());
    }

    /**
     * Lấy thống kê của user
     */
    public OrderStatistic getStatisticByUserId(UUID userId) {
        return orderStatisticRepository.findByUserId(userId)
                .orElse(OrderStatistic.builder()
                        .userId(userId)
                        .build());
    }
}
