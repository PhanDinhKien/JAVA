package com.microservice.orderservice.service;

import com.microservice.orderservice.dto.CreateOrderRequest;
import com.microservice.orderservice.dto.OrderDto;
import com.microservice.orderservice.event.OrderEventPublisher;
import com.microservice.orderservice.exception.ServiceUnavailableException;
import com.microservice.orderservice.grpc.UserGrpcClient;
import com.microservice.orderservice.model.Order;
import com.microservice.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserGrpcClient userGrpcClient;
    private final OrderEventPublisher orderEventPublisher;

    /**
     * Tạo đơn hàng mới - với Graceful Degradation
     *
     * Kịch bản:
     * 1. user-service SỐNG → validate user → tạo order (có userInfo)
     * 2. user-service CHẾT → bỏ qua validate → VẪN tạo order (không có userInfo)
     *    → Log warning để xử lý sau
     */
    @Transactional
    public OrderDto createOrder(CreateOrderRequest request) {
        boolean userValidated = false;

        try {
            // Bước 1: Kiểm tra user có tồn tại không (gọi gRPC)
            boolean userExists = userGrpcClient.checkUserExists(request.getUserId().toString());
            if (!userExists) {
                // User thật sự KHÔNG tồn tại (service trả lời rõ ràng)
                throw new RuntimeException("User not found with id: " + request.getUserId());
            }
            userValidated = true;
        } catch (ServiceUnavailableException e) {
            // user-service CHẾT → graceful degradation
            // Vẫn tạo order, nhưng log warning để xử lý sau
            log.warn("⚠️ GRACEFUL DEGRADATION: user-service không khả dụng. " +
                    "Tạo order mà KHÔNG validate userId: {}. Lý do: {}",
                    request.getUserId(), e.getMessage());
        }

        // Bước 2: Tạo và lưu order
        Order order = Order.builder()
                .userId(request.getUserId())
                .productName(request.getProductName())
                .quantity(request.getQuantity())
                .totalPrice(request.getTotalPrice())
                .build();

        Order savedOrder = orderRepository.save(order);

        if (userValidated) {
            log.info("✅ Order created (validated): {} for user: {}",
                    savedOrder.getId(), request.getUserId());
        } else {
            log.warn("⚠️ Order created (NOT validated): {} for user: {}",
                    savedOrder.getId(), request.getUserId());
        }

        // Bước 3: Publish event đến RabbitMQ (async, non-blocking)
        orderEventPublisher.publishOrderCreated(savedOrder);

        // Bước 4: Lấy thông tin user qua gRPC để enrich response
        return toDto(savedOrder);
    }

    /**
     * Lấy chi tiết đơn hàng kèm thông tin user (qua gRPC)
     */
    public OrderDto getOrderById(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + orderId));
        return toDto(order);
    }

    /**
     * Lấy tất cả đơn hàng của một user
     */
    public List<OrderDto> getOrdersByUserId(UUID userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Lấy tất cả đơn hàng
     */
    public List<OrderDto> getAllOrders() {
        return orderRepository.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Convert Order entity → OrderDto
     * Graceful: nếu gRPC lỗi → trả order không kèm userInfo (thay vì crash)
     */
    private OrderDto toDto(Order order) {
        // Gọi gRPC để lấy thông tin user
        // Nếu user-service chết → getUserById fallback trả null → userInfo = null
        OrderDto.UserInfo userInfo = userGrpcClient.getUserById(order.getUserId().toString());

        return OrderDto.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .productName(order.getProductName())
                .quantity(order.getQuantity())
                .totalPrice(order.getTotalPrice())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .userInfo(userInfo)
                .build();
    }
}
