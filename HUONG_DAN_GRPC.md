# Hướng Dẫn Tích Hợp gRPC Trong Microservices (Spring Boot 4.1)

## Mục Lục
1. [Tổng quan gRPC](#1-tổng-quan-grpc)
2. [So sánh gRPC vs REST](#2-so-sánh-grpc-vs-rest)
3. [Kiến trúc gRPC trong project](#3-kiến-trúc-grpc-trong-project)
4. [Bước 1: Tạo module grpc-proto (Protobuf definitions)](#4-bước-1-tạo-module-grpc-proto)
5. [Bước 2: Viết file .proto](#5-bước-2-viết-file-proto)
6. [Bước 3: Cấu hình gRPC Server (user-service)](#6-bước-3-cấu-hình-grpc-server-user-service)
7. [Bước 4: Cấu hình gRPC Client (order-service)](#7-bước-4-cấu-hình-grpc-client-order-service)
8. [Bước 5: Build & Chạy](#8-bước-5-build--chạy)
9. [Cách hoạt động end-to-end](#9-cách-hoạt-động-end-to-end)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Tổng Quan gRPC

### gRPC là gì?
**gRPC** (Google Remote Procedure Call) là một framework giao tiếp giữa các service, cho phép service A **gọi hàm** trực tiếp trên service B như gọi hàm local.

```
┌──────────────┐                          ┌──────────────┐
│ order-service│   gRPC call (binary)     │ user-service │
│              │ ───────────────────────> │              │
│ "Kiểm tra    │                          │ "User có     │
│  user tồn    │ <─────────────────────── │  tồn tại!"   │
│  tại không?" │   gRPC response          │              │
└──────────────┘                          └──────────────┘
```

### Các thành phần chính

| Thành phần | Mô tả | File |
|------------|-------|------|
| **Protobuf (.proto)** | Định nghĩa cấu trúc dữ liệu và API contract | `user.proto` |
| **Generated Code** | Code Java được tự động sinh từ .proto | `UserProtoServiceGrpc.java`, `UserIdRequest.java`, ... |
| **gRPC Server** | Service expose API qua gRPC | `UserGrpcService.java` |
| **gRPC Client** | Service gọi đến gRPC Server | `UserGrpcClient.java` |

---

## 2. So Sánh gRPC vs REST

| Tiêu chí | REST API | gRPC |
|----------|----------|------|
| **Protocol** | HTTP/1.1 (text-based) | HTTP/2 (binary) |
| **Format dữ liệu** | JSON (text, dễ đọc) | Protobuf (binary, nhỏ gọn) |
| **Tốc độ** | Chậm hơn | Nhanh hơn **2-10x** |
| **Type Safety** | Không (JSON tự do) | Có (schema từ .proto) |
| **Code Generation** | Không tự động | Tự động sinh client/server code |
| **Khi nào dùng** | API cho client (browser, mobile) | Giao tiếp giữa các microservice |
| **Streaming** | Không native | Hỗ trợ bi-directional streaming |

### Khi nào dùng gRPC?
- ✅ **Giao tiếp giữa backend services** (service-to-service)
- ✅ Cần hiệu suất cao, latency thấp
- ✅ Cần type safety (compile-time check)
- ❌ Không dùng cho browser client (browser chỉ hiểu REST/GraphQL)

---

## 3. Kiến Trúc gRPC Trong Project

```
project/
├── grpc-proto/              ← Module chung: chứa .proto + generated code
│   ├── pom.xml
│   └── src/main/proto/
│       └── user.proto       ← Định nghĩa API contract
│
├── user-service/            ← gRPC SERVER: expose user data
│   └── src/main/java/
│       └── grpc/
│           └── UserGrpcService.java
│
└── order-service/           ← gRPC CLIENT: gọi đến user-service
    └── src/main/java/
        ├── config/
        │   └── GrpcClientConfig.java
        └── grpc/
            └── UserGrpcClient.java
```

### Luồng giao tiếp

```
                    grpc-proto (shared module)
                    ┌────────────────────┐
                    │    user.proto       │
                    │  (API contract)     │
                    └─────────┬──────────┘
                              │
              ┌───────────────┼───────────────┐
              │ mvn install   │               │ dependency
              ▼               │               ▼
    ┌─────────────────┐       │     ┌─────────────────┐
    │  user-service   │       │     │  order-service   │
    │  (gRPC Server)  │       │     │  (gRPC Client)   │
    │                 │       │     │                  │
    │ Port 9091 (gRPC)│◄──────┘─────│ Gọi đến 9091    │
    │ Port 8081 (HTTP)│             │ Port 8082 (HTTP) │
    └─────────────────┘             └──────────────────┘
```

---

## 4. Bước 1: Tạo Module grpc-proto

### 4.1. Đăng ký module trong Parent POM

File: `pom.xml` (root)

```xml
<modules>
    <module>discovery-server</module>
    <module>api-gateway</module>
    <module>grpc-proto</module>        <!-- Thêm module này -->
    <module>user-service</module>
    <module>order-service</module>
</modules>
```

### 4.2. Tạo `grpc-proto/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" ...>
    <modelVersion>4.0.0</modelVersion>

    <!-- Kế thừa parent POM (Spring Boot 4.1) -->
    <parent>
        <groupId>com.microservice</groupId>
        <artifactId>microservice-parent</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>grpc-proto</artifactId>
    <name>gRPC Proto Definitions</name>
    <description>Shared Protobuf definitions for gRPC services</description>

    <dependencies>
        <!--
            grpc-stub: Chứa các abstract class "Stub" dùng để tạo gRPC client/server
            - BlockingStub: gọi gRPC đồng bộ (chờ response)
            - FutureStub: gọi gRPC bất đồng bộ (trả về Future)
            - AsyncStub: gọi gRPC bất đồng bộ (dùng StreamObserver callback)
        -->
        <dependency>
            <groupId>io.grpc</groupId>
            <artifactId>grpc-stub</artifactId>
        </dependency>

        <!--
            grpc-protobuf: Thư viện serialize/deserialize Protobuf message
            - Chuyển đổi Java object ↔ binary Protobuf format
            - Được dùng bởi generated code (UserIdRequest, UserResponse, ...)
        -->
        <dependency>
            <groupId>io.grpc</groupId>
            <artifactId>grpc-protobuf</artifactId>
        </dependency>

        <!--
            jakarta.annotation-api: Cung cấp annotation @Generated
            - Generated code từ .proto sử dụng @Generated annotation
            - Bắt buộc có để compile thành công
        -->
        <dependency>
            <groupId>jakarta.annotation</groupId>
            <artifactId>jakarta.annotation-api</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!--
                protobuf-maven-plugin: Plugin biên dịch file .proto
                - Đọc file .proto → sinh ra Java classes
                - Sử dụng protoc compiler (protobuf compiler)
                - Sử dụng protoc-gen-grpc-java để sinh gRPC stub classes

                Quá trình build:
                1. Plugin tìm file *.proto trong src/main/proto/
                2. Chạy protoc compiler → sinh message classes (UserIdRequest, UserResponse, ...)
                3. Chạy protoc-gen-grpc-java → sinh service class (UserProtoServiceGrpc)
                4. Output vào target/generated-sources/
            -->
            <plugin>
                <groupId>io.github.ascopes</groupId>
                <artifactId>protobuf-maven-plugin</artifactId>
                <version>3.8.0</version>
                <configuration>
                    <!-- Phiên bản protoc compiler -->
                    <protocVersion>4.31.1</protocVersion>
                    <binaryMavenPlugins>
                        <!--
                            protoc-gen-grpc-java: Plugin cho protoc
                            - Sinh ra class XxxGrpc với các inner class:
                              - XxxImplBase: abstract class cho server implement
                              - XxxBlockingStub: cho client gọi đồng bộ
                              - XxxFutureStub: cho client gọi bất đồng bộ
                        -->
                        <binaryMavenPlugin>
                            <groupId>io.grpc</groupId>
                            <artifactId>protoc-gen-grpc-java</artifactId>
                            <version>1.74.0</version>
                        </binaryMavenPlugin>
                    </binaryMavenPlugins>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <!-- Chạy generate trong phase generate-sources -->
                            <goal>generate</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>

            <!--
                Skip spring-boot-maven-plugin repackage
                Vì grpc-proto là LIBRARY (không phải application)
                → Không cần đóng gói thành executable JAR
            -->
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <skip>true</skip>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### Giải thích thư viện

| Thư viện | Mục đích |
|----------|----------|
| `grpc-stub` | Chứa các abstract Stub class (BlockingStub, FutureStub) — nền tảng cho client gọi gRPC |
| `grpc-protobuf` | Serialize/deserialize Java object ↔ binary Protobuf format |
| `jakarta.annotation-api` | Cung cấp `@Generated` annotation cho code được sinh tự động |
| `protobuf-maven-plugin` | Plugin Maven biên dịch `.proto` → Java classes |
| `protoc-gen-grpc-java` | Plugin mở rộng cho protoc, sinh ra `XxxGrpc` class với Stub/ImplBase |

---

## 5. Bước 2: Viết File .proto

### 5.1. Tạo file `grpc-proto/src/main/proto/user.proto`

```protobuf
// Phiên bản Protobuf syntax (luôn dùng proto3)
syntax = "proto3";

// Tùy chọn cho Java code generation:
// java_multiple_files = true: Mỗi message/service sinh ra 1 file Java riêng
//   → Thay vì gộp tất cả vào 1 file lớn, dễ quản lý hơn
option java_multiple_files = true;

// java_package: Package Java cho generated code
//   → Import trong code: import com.microservice.grpc.user.UserIdRequest;
option java_package = "com.microservice.grpc.user";

// java_outer_classname: Tên outer class (chứa các inner class nếu java_multiple_files = false)
option java_outer_classname = "UserProto";

// Package protobuf (namespace để tránh xung đột tên)
package user;

// ═══════════════════════════════════════════════════════════════
// SERVICE: Định nghĩa các RPC method (giống như Controller endpoints)
// ═══════════════════════════════════════════════════════════════
service UserProtoService {

  // RPC method 1: Lấy thông tin user theo ID
  // Tương đương REST: GET /api/users/{id}
  // Input: UserIdRequest → Output: UserResponse
  rpc GetUserById (UserIdRequest) returns (UserResponse);

  // RPC method 2: Kiểm tra user có tồn tại không
  // Tương đương REST: GET /api/users/{id}/exists
  // Input: UserIdRequest → Output: UserExistsResponse
  rpc CheckUserExists (UserIdRequest) returns (UserExistsResponse);
}

// ═══════════════════════════════════════════════════════════════
// MESSAGES: Định nghĩa cấu trúc dữ liệu (giống DTO/Model)
// ═══════════════════════════════════════════════════════════════

// Request message - truyền user ID để tìm kiếm
// Số 1, 2, 3... là field number (dùng cho binary encoding, KHÔNG PHẢI thứ tự)
message UserIdRequest {
  string user_id = 1;     // UUID dạng string: "caee30a8-98ed-49b0-..."
}

// Response message - thông tin chi tiết của user
message UserResponse {
  string id = 1;           // UUID
  string name = 2;         // Tên user
  string email = 3;        // Email
  string phone_number = 4; // Số điện thoại
  string status = 5;       // Trạng thái (ACTIVE, INACTIVE, ...)
  bool found = 6;          // true nếu tìm thấy user, false nếu không
}

// Response message - kết quả kiểm tra user tồn tại
message UserExistsResponse {
  bool exists = 1;         // true/false
}
```

### 5.2. Code được sinh tự động từ .proto

Sau khi chạy `mvn install`, plugin sẽ sinh ra các file Java:

| File sinh ra | Từ phần nào trong .proto | Mục đích |
|-------------|-------------------------|----------|
| `UserIdRequest.java` | `message UserIdRequest` | Java class cho request |
| `UserResponse.java` | `message UserResponse` | Java class cho response |
| `UserExistsResponse.java` | `message UserExistsResponse` | Java class cho response |
| `UserProtoServiceGrpc.java` | `service UserProtoService` | Chứa `ImplBase` (server) và `BlockingStub` (client) |

### 5.3. Build grpc-proto

```bash
# Biên dịch .proto → Java classes → install vào local Maven repository
.\mvnw.cmd -pl grpc-proto install
```

> **QUAN TRỌNG**: Phải chạy lệnh này TRƯỚC khi build user-service và order-service, vì chúng depend on `grpc-proto`.

---

## 6. Bước 3: Cấu Hình gRPC Server (user-service)

### 6.1. Thêm dependency vào `user-service/pom.xml`

```xml
<!--
    spring-boot-starter-grpc-server (Spring Boot 4.1 native)
    Mục đích: Tự động khởi tạo gRPC server trong Spring Boot
    - Tự phát hiện các class có @GrpcService annotation
    - Tự tạo gRPC server và bind trên port cấu hình
    - Tích hợp sẵn với Spring DI (dependency injection)

    Lưu ý: Đây là Spring Boot 4.1 NATIVE gRPC support
    Khác với thư viện bên thứ 3 (net.devh:grpc-spring-boot-starter)
-->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-grpc-server</artifactId>
</dependency>

<!--
    Shared gRPC Proto: Module chứa generated code từ .proto
    - UserIdRequest, UserResponse, UserExistsResponse
    - UserProtoServiceGrpc (ImplBase cho server implement)
-->
<dependency>
    <groupId>com.microservice</groupId>
    <artifactId>grpc-proto</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 6.2. Cấu hình port gRPC server trong `application.yml`

```yaml
server:
  port: 8081             # Port HTTP (REST API)

spring:
  application:
    name: user-service
  grpc:
    server:
      port: 9091         # Port gRPC server (QUAN TRỌNG: phải nằm dưới spring:)
```

> **⚠️ LƯU Ý**: Spring Boot 4.1 dùng property `spring.grpc.server.port`
> (KHÔNG PHẢI `grpc.server.port` của thư viện bên thứ 3)

### 6.3. Implement gRPC Service

Tạo file: `user-service/src/main/java/.../grpc/UserGrpcService.java`

```java
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

// @GrpcService: Annotation của Spring Boot 4.1
// → Spring tự phát hiện class này và đăng ký vào gRPC server
// → Tương tự @RestController cho REST API
import org.springframework.grpc.server.service.GrpcService;

import java.util.Optional;
import java.util.UUID;

/**
 * gRPC Server implementation
 *
 * Class này EXTEND từ UserProtoServiceGrpc.UserProtoServiceImplBase
 * (class được sinh tự động từ user.proto)
 *
 * Mỗi method tương ứng với 1 rpc trong .proto:
 * - rpc GetUserById     → method getUserById()
 * - rpc CheckUserExists → method checkUserExists()
 */
@GrpcService                    // ← Đánh dấu đây là gRPC service (Spring sẽ tự đăng ký)
@RequiredArgsConstructor        // ← Lombok: tự tạo constructor với final fields
@Slf4j                          // ← Lombok: tự tạo biến `log`
public class UserGrpcService extends UserProtoServiceGrpc.UserProtoServiceImplBase {
    //                          ↑ EXTEND từ generated ImplBase class

    // Inject repository qua constructor (nhờ @RequiredArgsConstructor)
    private final UserRepository userRepository;

    /**
     * Implementation cho rpc GetUserById
     *
     * @param request          - UserIdRequest (chứa user_id)
     * @param responseObserver - Callback để gửi response về cho client
     *
     * Luồng xử lý:
     * 1. Parse userId từ request
     * 2. Query database
     * 3. Build response (UserResponse)
     * 4. Gửi response qua responseObserver.onNext()
     * 5. Kết thúc bằng responseObserver.onCompleted()
     */
    @Override
    public void getUserById(UserIdRequest request,
                            StreamObserver<UserResponse> responseObserver) {
        log.info("gRPC: Received GetUserById request for userId: {}",
                 request.getUserId());

        try {
            UUID userId = UUID.fromString(request.getUserId());
            Optional<User> userOpt = userRepository.findById(userId);

            UserResponse response;
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                // Dùng Builder pattern (Protobuf style) để tạo response
                response = UserResponse.newBuilder()
                        .setId(user.getId().toString())
                        .setName(user.getName())
                        .setEmail(user.getEmail())
                        .setPhoneNumber(user.getPhoneNumber() != null
                                ? user.getPhoneNumber() : "")
                        .setStatus(user.getStatus())
                        .setFound(true)     // Đánh dấu tìm thấy
                        .build();
                log.info("gRPC: Found user: {}", user.getName());
            } else {
                response = UserResponse.newBuilder()
                        .setFound(false)    // Không tìm thấy
                        .build();
                log.warn("gRPC: User not found with id: {}", request.getUserId());
            }

            // GỬI response về client
            responseObserver.onNext(response);
            // KẾT THÚC RPC call
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            log.error("gRPC: Invalid UUID format: {}", request.getUserId());
            responseObserver.onNext(
                UserResponse.newBuilder().setFound(false).build()
            );
            responseObserver.onCompleted();
        }
    }

    /**
     * Implementation cho rpc CheckUserExists
     * Đơn giản hơn: chỉ kiểm tra user có tồn tại trong DB không
     */
    @Override
    public void checkUserExists(UserIdRequest request,
                                StreamObserver<UserExistsResponse> responseObserver) {
        log.info("gRPC: Received CheckUserExists for userId: {}",
                 request.getUserId());

        try {
            UUID userId = UUID.fromString(request.getUserId());
            boolean exists = userRepository.existsById(userId);

            UserExistsResponse response = UserExistsResponse.newBuilder()
                    .setExists(exists)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onNext(
                UserExistsResponse.newBuilder().setExists(false).build()
            );
            responseObserver.onCompleted();
        }
    }
}
```

### Giải thích pattern `StreamObserver`

```java
// StreamObserver là callback pattern của gRPC:
responseObserver.onNext(response);     // Gửi data (có thể gọi nhiều lần cho streaming)
responseObserver.onCompleted();        // Đánh dấu kết thúc (BẮT BUỘC phải gọi)
// responseObserver.onError(exception); // Nếu có lỗi (thay cho onCompleted)
```

---

## 7. Bước 4: Cấu Hình gRPC Client (order-service)

### 7.1. Thêm dependency vào `order-service/pom.xml`

```xml
<!--
    spring-boot-starter-grpc-client (Spring Boot 4.1 native)
    Mục đích: Cung cấp gRPC client infrastructure
    - ManagedChannel: quản lý kết nối TCP đến gRPC server
    - Stub: proxy object để gọi remote method
    - Tự động retry, reconnect khi mất kết nối
-->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-grpc-client</artifactId>
</dependency>

<!--
    Shared gRPC Proto: Cùng module generated code
    - UserProtoServiceGrpc.BlockingStub (cho client gọi đồng bộ)
    - UserIdRequest, UserResponse, UserExistsResponse
-->
<dependency>
    <groupId>com.microservice</groupId>
    <artifactId>grpc-proto</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 7.2. Cấu hình kết nối trong `application.yml`

```yaml
# gRPC client - kết nối đến user-service gRPC server
grpc:
  client:
    user-service:
      host: localhost     # IP/hostname của user-service
      port: 9091          # Port gRPC server của user-service
```

### 7.3. Tạo Config class cho gRPC Client

Tạo file: `order-service/src/main/java/.../config/GrpcClientConfig.java`

```java
package com.microservice.orderservice.config;

import com.microservice.grpc.user.UserProtoServiceGrpc;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình gRPC Client
 *
 * Tạo các Bean cần thiết để gọi gRPC:
 * 1. ManagedChannel: Kết nối TCP đến gRPC server
 * 2. BlockingStub: Proxy object để gọi remote method
 */
@Configuration
public class GrpcClientConfig {

    // Đọc host từ application.yml
    // Giá trị mặc định: localhost (nếu không cấu hình)
    @Value("${grpc.client.user-service.host:localhost}")
    private String userServiceHost;

    // Đọc port từ application.yml
    @Value("${grpc.client.user-service.port:9091}")
    private int userServicePort;

    /**
     * Tạo BlockingStub Bean
     *
     * BlockingStub = proxy object cho phép gọi gRPC method đồng bộ
     * Khi gọi stub.getUserById(request):
     *   1. Serialize request thành binary (Protobuf)
     *   2. Gửi qua TCP đến user-service:9091
     *   3. Chờ response
     *   4. Deserialize response thành Java object
     *   5. Return
     */
    @Bean
    public UserProtoServiceGrpc.UserProtoServiceBlockingStub userServiceBlockingStub() {

        // ManagedChannel: quản lý kết nối TCP đến gRPC server
        // - forAddress(host, port): địa chỉ server
        // - usePlaintext(): KHÔNG dùng TLS (chỉ cho development)
        //   → Production phải dùng useTransportSecurity() + certificate
        var channel = ManagedChannelBuilder
                .forAddress(userServiceHost, userServicePort)
                .usePlaintext()    // ← Không mã hóa (chỉ dùng cho dev)
                .build();

        // Tạo BlockingStub từ channel
        // BlockingStub: gọi đồng bộ (block thread cho đến khi có response)
        // Còn có: newFutureStub() (async), newStub() (async + StreamObserver)
        return UserProtoServiceGrpc.newBlockingStub(channel);
    }
}
```

### 7.4. Tạo gRPC Client wrapper

Tạo file: `order-service/src/main/java/.../grpc/UserGrpcClient.java`

```java
package com.microservice.orderservice.grpc;

import com.microservice.grpc.user.UserIdRequest;
import com.microservice.grpc.user.UserProtoServiceGrpc;
import com.microservice.grpc.user.UserResponse;
import com.microservice.orderservice.dto.OrderDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * gRPC Client - gọi đến user-service để lấy/kiểm tra thông tin User
 *
 * Cách hoạt động:
 * 1. GrpcClientConfig tạo channel + blocking stub bean
 * 2. Stub được inject vào đây qua constructor (nhờ @RequiredArgsConstructor)
 * 3. Gọi các method RPC đã định nghĩa trong file .proto
 *
 * Flow: OrderService → UserGrpcClient → (gRPC) → UserGrpcService
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserGrpcClient {

    // BlockingStub được inject từ GrpcClientConfig
    // Đây là "proxy" để gọi gRPC method trên user-service
    private final UserProtoServiceGrpc.UserProtoServiceBlockingStub userStub;

    /**
     * Gọi gRPC đến user-service để lấy thông tin user
     *
     * Tương đương REST call: GET http://user-service:8081/api/users/{userId}
     * Nhưng nhanh hơn vì dùng binary protocol (Protobuf over HTTP/2)
     */
    public OrderDto.UserInfo getUserById(String userId) {
        log.info("gRPC Client: Calling GetUserById for userId: {}", userId);

        try {
            // Bước 1: Tạo request message (Protobuf Builder pattern)
            UserIdRequest request = UserIdRequest.newBuilder()
                    .setUserId(userId)
                    .build();

            // Bước 2: Gọi gRPC method (đồng bộ - block cho đến khi có response)
            // Bên dưới: serialize → TCP → deserialize
            UserResponse response = userStub.getUserById(request);

            // Bước 3: Xử lý response
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
            // Xử lý lỗi kết nối (UNAVAILABLE, DEADLINE_EXCEEDED, ...)
            log.error("gRPC Client: Error calling user-service: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Kiểm tra user có tồn tại không (qua gRPC)
     * Gọn hơn getUserById - chỉ trả về true/false
     */
    public boolean checkUserExists(String userId) {
        log.info("gRPC Client: Checking if user exists: {}", userId);

        try {
            UserIdRequest request = UserIdRequest.newBuilder()
                    .setUserId(userId)
                    .build();

            // Gọi gRPC method checkUserExists
            return userStub.checkUserExists(request).getExists();
        } catch (Exception e) {
            log.error("gRPC Client: Error checking user existence: {}",
                       e.getMessage());
            return false;   // Mặc định: user không tồn tại nếu có lỗi
        }
    }
}
```

### 7.5. Sử dụng gRPC Client trong Business Logic

File: `order-service/src/main/java/.../service/OrderService.java`

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserGrpcClient userGrpcClient;   // ← Inject gRPC client

    /**
     * Tạo đơn hàng mới
     * 1. Gọi gRPC kiểm tra user tồn tại
     * 2. Lưu order vào DB
     * 3. Gọi gRPC lấy thông tin user để enrich response
     */
    @Transactional
    public OrderDto createOrder(CreateOrderRequest request) {
        // ═══ Bước 1: Gọi gRPC đến user-service ═══
        boolean userExists = userGrpcClient.checkUserExists(
            request.getUserId().toString()
        );
        if (!userExists) {
            throw new RuntimeException("User not found: " + request.getUserId());
        }

        // ═══ Bước 2: Lưu order vào DB ═══
        Order order = Order.builder()
                .userId(request.getUserId())
                .productName(request.getProductName())
                .quantity(request.getQuantity())
                .totalPrice(request.getTotalPrice())
                .build();

        Order savedOrder = orderRepository.save(order);

        // ═══ Bước 3: Lấy thông tin user (gRPC) để trả về ═══
        return toDto(savedOrder);
    }

    private OrderDto toDto(Order order) {
        // Gọi gRPC để lấy thông tin user
        OrderDto.UserInfo userInfo = userGrpcClient.getUserById(
            order.getUserId().toString()
        );

        return OrderDto.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .productName(order.getProductName())
                .quantity(order.getQuantity())
                .totalPrice(order.getTotalPrice())
                .status(order.getStatus())
                .userInfo(userInfo)   // ← Kèm thông tin user từ gRPC
                .build();
    }
}
```

---

## 8. Bước 5: Build & Chạy

### Thứ tự build QUAN TRỌNG

```bash
# 1. Build grpc-proto TRƯỚC (sinh generated code + install vào local repo)
.\mvnw.cmd -pl grpc-proto install

# 2. Chạy discovery-server
.\mvnw.cmd -pl discovery-server spring-boot:run

# 3. Chạy user-service (gRPC server phải start trước client)
.\mvnw.cmd -pl user-service spring-boot:run

# 4. Chạy order-service (gRPC client)
.\mvnw.cmd -pl order-service spring-boot:run

# 5. Chạy api-gateway
.\mvnw.cmd -pl api-gateway spring-boot:run
```

### Kiểm tra gRPC server đang chạy

```bash
# Kiểm tra port 9091 đang listening
netstat -an | findstr "9091"

# Expected output:
# TCP    0.0.0.0:9091    0.0.0.0:0    LISTENING
```

---

## 9. Cách Hoạt Động End-to-End

### Khi gọi POST /api/orders:

```
Browser/Swagger                     API Gateway              order-service              user-service
     │                                  │                         │                          │
     │ POST /api/orders                 │                         │                          │
     │ {userId: "abc-123", ...}         │                         │                          │
     │ ──────────────────────────────>  │                         │                          │
     │                                  │ Route to order-service  │                          │
     │                                  │ ──────────────────────> │                          │
     │                                  │                         │                          │
     │                                  │                         │ gRPC: CheckUserExists    │
     │                                  │                         │ (TCP binary, port 9091)  │
     │                                  │                         │ ──────────────────────>  │
     │                                  │                         │                          │ Query DB
     │                                  │                         │                          │
     │                                  │                         │ <──────────────────────  │
     │                                  │                         │ Response: {exists: true}  │
     │                                  │                         │                          │
     │                                  │                         │ Save order to DB          │
     │                                  │                         │                          │
     │                                  │                         │ gRPC: GetUserById        │
     │                                  │                         │ ──────────────────────>  │
     │                                  │                         │                          │ Query DB
     │                                  │                         │ <──────────────────────  │
     │                                  │                         │ Response: {name, email}   │
     │                                  │                         │                          │
     │                                  │ <────────────────────── │                          │
     │ <──────────────────────────────  │ JSON Response           │                          │
     │ {order + userInfo}               │                         │                          │
```

### Response mẫu:

```json
{
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "userId": "abc-123",
  "productName": "Máy Khoan",
  "quantity": 2,
  "totalPrice": 1500000,
  "status": "PENDING",
  "userInfo": {
    "id": "abc-123",
    "name": "Phan Kien",
    "email": "phankien@example.com",
    "phoneNumber": "0912345678",
    "status": "ACTIVE"
  }
}
```

---

## 10. Troubleshooting

| Lỗi | Nguyên nhân | Giải pháp |
|-----|-------------|-----------|
| `UNAVAILABLE: io exception` | gRPC server chưa start hoặc port sai | Kiểm tra `netstat -an \| findstr "9091"` có LISTENING không |
| `Could not find artifact grpc-proto` | Chưa chạy `mvn install` cho grpc-proto | Chạy `.\mvnw.cmd -pl grpc-proto install` |
| gRPC server không start (port không listen) | Property sai: dùng `grpc.server.port` thay vì `spring.grpc.server.port` | Spring Boot 4.1: phải dùng `spring.grpc.server.port` |
| `ClassNotFoundException: UserProtoServiceGrpc` | Generated code chưa được sinh | Chạy `.\mvnw.cmd -pl grpc-proto clean install` |
| `DEADLINE_EXCEEDED` | Server xử lý quá lâu | Tăng timeout hoặc tối ưu query DB |
| `User not found with id: xxx` | gRPC kết nối thất bại → `checkUserExists()` trả `false` | Kiểm tra logs để xem lỗi gRPC cụ thể |

---

## Cấu Trúc File Tổng Hợp

```
project/
├── grpc-proto/                              ← MODULE CHUNG
│   ├── pom.xml                              ← Dependencies: grpc-stub, grpc-protobuf
│   └── src/main/proto/
│       └── user.proto                       ← Định nghĩa service + message
│
├── user-service/                            ← gRPC SERVER
│   ├── pom.xml                              ← Dep: spring-boot-starter-grpc-server, grpc-proto
│   └── src/main/
│       ├── resources/
│       │   └── application.yml              ← spring.grpc.server.port: 9091
│       └── java/.../grpc/
│           └── UserGrpcService.java         ← Implement RPC methods
│
└── order-service/                           ← gRPC CLIENT
    ├── pom.xml                              ← Dep: spring-boot-starter-grpc-client, grpc-proto
    └── src/main/
        ├── resources/
        │   └── application.yml              ← grpc.client.user-service.host/port
        └── java/
            ├── config/
            │   └── GrpcClientConfig.java    ← Tạo ManagedChannel + BlockingStub bean
            ├── grpc/
            │   └── UserGrpcClient.java      ← Wrapper gọi gRPC method
            └── service/
                └── OrderService.java        ← Business logic sử dụng gRPC client
```
