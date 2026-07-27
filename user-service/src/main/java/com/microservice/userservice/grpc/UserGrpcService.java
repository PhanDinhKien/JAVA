package com.microservice.userservice.grpc;

import com.microservice.grpc.user.UserIdRequest;
import com.microservice.grpc.user.UserExistsResponse;
import com.microservice.grpc.user.UserProtoServiceGrpc;
import com.microservice.grpc.user.UserResponse;
import com.microservice.userservice.model.User;
import com.microservice.userservice.repository.UserRepository;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.grpc.server.service.GrpcService;

import java.util.Optional;
import java.util.UUID;

/**
 * gRPC Server implementation - expose User data qua gRPC
 * Được order-service (và các service khác) gọi đến
 */
@GrpcService
@RequiredArgsConstructor
@Slf4j
public class UserGrpcService extends UserProtoServiceGrpc.UserProtoServiceImplBase {

    private final UserRepository userRepository;

    @Override
    public void getUserById(UserIdRequest request, StreamObserver<UserResponse> responseObserver) {
        log.info("gRPC: Received GetUserById request for userId: {}", request.getUserId());

        try {
            UUID userId = UUID.fromString(request.getUserId());
            Optional<User> userOpt = userRepository.findById(userId);

            UserResponse response;
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                response = UserResponse.newBuilder()
                        .setId(user.getId().toString())
                        .setName(user.getName())
                        .setEmail(user.getEmail())
                        .setPhoneNumber(user.getPhoneNumber() != null ? user.getPhoneNumber() : "")
                        .setStatus(user.getStatus())
                        .setFound(true)
                        .build();
                log.info("gRPC: Found user: {}", user.getName());
            } else {
                response = UserResponse.newBuilder()
                        .setFound(false)
                        .build();
                log.warn("gRPC: User not found with id: {}", request.getUserId());
            }

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            log.error("gRPC: Invalid UUID format: {}", request.getUserId());
            responseObserver.onNext(UserResponse.newBuilder().setFound(false).build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void checkUserExists(UserIdRequest request, StreamObserver<UserExistsResponse> responseObserver) {
        log.info("gRPC: Received CheckUserExists request for userId: {}", request.getUserId());

        try {
            UUID userId = UUID.fromString(request.getUserId());
            boolean exists = userRepository.existsById(userId);

            UserExistsResponse response = UserExistsResponse.newBuilder()
                    .setExists(exists)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onNext(UserExistsResponse.newBuilder().setExists(false).build());
            responseObserver.onCompleted();
        }
    }
}
