package com.microservice.orderservice.grpc;

import com.microservice.grpc.user.UserIdRequest;
import com.microservice.grpc.user.UserProtoServiceGrpc;
import com.microservice.grpc.user.UserResponse;
import com.microservice.orderservice.dto.OrderDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * gRPC Client - gọi đến user-service để lấy thông tin User
 *
 * Cách hoạt động:
 * 1. GrpcClientConfig tạo channel + blocking stub bean
 * 2. Stub được inject vào đây qua constructor
 * 3. Gọi các method RPC đã định nghĩa trong file .proto
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserGrpcClient {

    private final UserProtoServiceGrpc.UserProtoServiceBlockingStub userStub;

    /**
     * Gọi gRPC đến user-service để lấy thông tin user
     */
    public OrderDto.UserInfo getUserById(String userId) {
        log.info("gRPC Client: Calling GetUserById for userId: {}", userId);

        try {
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
        } catch (Exception e) {
            log.error("gRPC Client: Error calling user-service: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Kiểm tra user có tồn tại không (qua gRPC)
     */
    public boolean checkUserExists(String userId) {
        log.info("gRPC Client: Checking if user exists: {}", userId);

        try {
            UserIdRequest request = UserIdRequest.newBuilder()
                    .setUserId(userId)
                    .build();

            return userStub.checkUserExists(request).getExists();
        } catch (Exception e) {
            log.error("gRPC Client: Error checking user existence: {}", e.getMessage());
            return false;
        }
    }
}
