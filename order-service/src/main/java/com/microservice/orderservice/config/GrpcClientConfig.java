package com.microservice.orderservice.config;

import com.microservice.grpc.user.UserProtoServiceGrpc;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình gRPC Client
 * Tạo blocking stub bean để inject vào các service khác
 */
@Configuration
public class GrpcClientConfig {

    @Value("${grpc.client.user-service.host:localhost}")
    private String userServiceHost;

    @Value("${grpc.client.user-service.port:9091}")
    private int userServicePort;

    @Bean
    public UserProtoServiceGrpc.UserProtoServiceBlockingStub userServiceBlockingStub() {
        var channel = ManagedChannelBuilder
                .forAddress(userServiceHost, userServicePort)
                .usePlaintext()
                .build();

        return UserProtoServiceGrpc.newBlockingStub(channel);
    }
}
