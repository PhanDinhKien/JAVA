package com.microservice.orderservice.grpc;

import com.microservice.grpc.user.UserIdRequest;
import com.microservice.grpc.user.UserProtoServiceGrpc;
import com.microservice.grpc.user.UserResponse;
import com.microservice.orderservice.dto.OrderDto;
import com.microservice.orderservice.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * gRPC Client với Resilience4j fault tolerance
 *
 * Luồng xử lý khi gọi gRPC:
 * 1. @Retry: thử lại tối đa 3 lần (nếu lỗi tạm thời)
 * 2. @CircuitBreaker: nếu lỗi liên tục → "ngắt mạch" → gọi fallback ngay
 * 3. Fallback: throw ServiceUnavailableException (để caller xử lý graceful)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserGrpcClient {

    private final UserProtoServiceGrpc.UserProtoServiceBlockingStub userStub;

    /**
     * Gọi gRPC đến user-service để lấy thông tin user
     *
     * @CircuitBreaker: Khi lỗi liên tục → ngắt mạch → gọi fallback
     * @Retry: Tự động thử lại khi gặp lỗi tạm thời
     */
    @CircuitBreaker(name = "userService", fallbackMethod = "getUserByIdFallback")
    @Retry(name = "userService")
    public OrderDto.UserInfo getUserById(String userId) {
        log.info("gRPC Client: Calling GetUserById for userId: {}", userId);

        UserIdRequest request = UserIdRequest.newBuilder()
                .setUserId(userId)
                .build();

        UserResponse response = userStub.getUserById(request);

        if (response.getFound()) {
            log.info("gRPC Client: User found - {}", response.getName());
            return OrderDto.UserInfo.builder()
                    .id(response.getId())
                    .name(response.getName())
                    .email(response.getEmail())
                    .phoneNumber(response.getPhoneNumber())
                    .status(response.getStatus())
                    .build();
        } else {
            log.warn("gRPC Client: User not found for id: {}", userId);
            return null;
        }
    }

    /**
     * Kiểm tra user có tồn tại không (qua gRPC)
     */
    @CircuitBreaker(name = "userService", fallbackMethod = "checkUserExistsFallback")
    @Retry(name = "userService")
    public boolean checkUserExists(String userId) {
        log.info("gRPC Client: Checking if user exists: {}", userId);

        UserIdRequest request = UserIdRequest.newBuilder()
                .setUserId(userId)
                .build();

        return userStub.checkUserExists(request).getExists();
    }

    // ═══════════════════════════════════════════════════════════════
    // FALLBACK METHODS
    // Được gọi khi Circuit Breaker mở hoặc sau khi Retry hết lần
    // Signature: cùng tham số + thêm Throwable cuối cùng
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fallback cho getUserById
     * Khi user-service chết → trả null (order vẫn tạo được, chỉ thiếu userInfo)
     */
    private OrderDto.UserInfo getUserByIdFallback(String userId, Throwable throwable) {
        log.warn("gRPC FALLBACK: getUserById - user-service không khả dụng. userId: {}, error: {}",
                userId, throwable.getMessage());
        return null;
    }

    /**
     * Fallback cho checkUserExists
     * Khi user-service chết → throw ServiceUnavailableException
     * (để OrderService phân biệt "service chết" vs "user không tồn tại")
     */
    private boolean checkUserExistsFallback(String userId, Throwable throwable) {
        log.error("gRPC FALLBACK: checkUserExists - user-service không khả dụng. userId: {}, error: {}",
                userId, throwable.getMessage());
        throw new ServiceUnavailableException(
                "user-service",
                "User service không khả dụng, không thể validate userId: " + userId,
                throwable
        );
    }
}
