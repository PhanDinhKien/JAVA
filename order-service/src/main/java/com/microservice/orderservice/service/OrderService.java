package com.microservice.orderservice.service;

import com.microservice.orderservice.dto.CreateOrderRequest;
import com.microservice.orderservice.dto.OrderDto;
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

    /**
     * Tạo đơn hàng mới
     * 1. Kiểm tra user tồn tại qua gRPC
     * 2. Lưu order vào DB
     * 3. Lấy thông tin user qua gRPC để trả về response
     */
    @Transactional
    public OrderDto createOrder(CreateOrderRequest request) {
        // Bước 1: Kiểm tra user có tồn tại không (gọi gRPC)
        boolean userExists = userGrpcClient.checkUserExists(request.getUserId().toString());
        if (!userExists) {
            throw new RuntimeException("User not found with id: " + request.getUserId());
        }

        // Bước 2: Tạo và lưu order
        Order order = Order.builder()
                .userId(request.getUserId())
                .productName(request.getProductName())
                .quantity(request.getQuantity())
                .totalPrice(request.getTotalPrice())
                .build();

        Order savedOrder = orderRepository.save(order);
        log.info("Order created: {} for user: {}", savedOrder.getId(), request.getUserId());

        // Bước 3: Lấy thông tin user qua gRPC để enrich response
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
     * Convert Order entity → OrderDto (kèm gọi gRPC lấy user info)
     */
    private OrderDto toDto(Order order) {
        // Gọi gRPC để lấy thông tin user
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
