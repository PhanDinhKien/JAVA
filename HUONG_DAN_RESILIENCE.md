# Hướng Dẫn Tích Hợp Resilience4j - Fault Tolerance cho Microservices

## Mục Lục
1. [Vấn đề cần giải quyết](#1-vấn-đề-cần-giải-quyết)
2. [Resilience4j là gì?](#2-resilience4j-là-gì)
3. [Các pattern chính](#3-các-pattern-chính)
4. [Thêm thư viện](#4-thêm-thư-viện)
5. [Tạo Custom Exception](#5-tạo-custom-exception)
6. [Cấu hình Circuit Breaker + Retry](#6-cấu-hình-circuit-breaker--retry)
7. [Implement Fallback Methods](#7-implement-fallback-methods)
8. [Graceful Degradation trong Business Logic](#8-graceful-degradation-trong-business-logic)
9. [Global Exception Handler](#9-global-exception-handler)
10. [Cấu hình trong application.yml](#10-cấu-hình-trong-applicationyml)
11. [Kiểm tra hoạt động](#11-kiểm-tra-hoạt-động)

---

## 1. Vấn Đề Cần Giải Quyết

### Trước khi có Resilience4j

```
order-service                          user-service
     │                                      │
     │ gRPC: checkUserExists("abc")         │
     │ ─────────────────────────────────>   │ ← SERVICE CHẾT!
     │                                      ✕
     │ Exception: UNAVAILABLE               │
     │                                      │
     │ catch → return false                 │
     │ → "User not found" ← SAI!           │
     │   (user có thể tồn tại)             │
```

**Vấn đề**: Không phân biệt được:
- ❌ User thật sự không tồn tại (service trả lời `exists: false`)
- ❌ Service chết nên không trả lời được

### Sau khi có Resilience4j

```
order-service                          user-service
     │                                      │
     │ gRPC: checkUserExists("abc")         │
     │ → Retry lần 1 ────────────────────>  ✕ CHẾT
     │ → Retry lần 2 ────────────────────>  ✕ CHẾT
     │ → Retry lần 3 ────────────────────>  ✕ CHẾT
     │                                      │
     │ Circuit Breaker ghi nhận lỗi         │
     │ → Fallback: ServiceUnavailableException
     │                                      │
     │ OrderService catch exception         │
     │ → GRACEFUL DEGRADATION               │
     │ → Vẫn tạo order ✅                   │
     │ → Log warning ⚠️                     │
     │                                      │
     │ (Sau 30s, Circuit Breaker thử lại)   │
     │ → Nếu service sống → hoạt động BT   │
```

---

## 2. Resilience4j Là Gì?

**Resilience4j** là thư viện fault tolerance cho Java, cung cấp các pattern để bảo vệ service khỏi lỗi cascade khi service phụ thuộc gặp sự cố.

| Pattern | Mô tả | Ví dụ thực tế |
|---------|-------|---------------|
| **Circuit Breaker** | "Cầu dao điện" — ngắt mạch khi lỗi liên tục | Quá nhiều lỗi → ngắt → không gọi nữa |
| **Retry** | Tự thử lại khi lỗi tạm thời | Mạng flicker → thử lại 3 lần |
| **Rate Limiter** | Giới hạn số request/giây | Tránh quá tải service |
| **Bulkhead** | Cô lập resource (thread pool) | 1 service chết không kéo theo tất cả |
| **Time Limiter** | Giới hạn thời gian chờ | Không chờ mãi khi service chậm |

---

## 3. Các Pattern Chính

### 3.1. Circuit Breaker

```
    ┌──────────────────────────────────────────────┐
    │                                              │
    │   CLOSED (bình thường)                       │
    │   ├── Mọi request đều được gửi đi           │
    │   └── Đếm số lỗi                            │
    │        │                                     │
    │        │ Lỗi >= 50% (threshold)              │
    │        ▼                                     │
    │   OPEN (ngắt mạch)                           │
    │   ├── KHÔNG gửi request → gọi fallback ngay  │
    │   ├── Response nhanh (không chờ timeout)      │
    │   └── Chờ 30 giây                            │
    │        │                                     │
    │        │ Hết thời gian chờ                   │
    │        ▼                                     │
    │   HALF_OPEN (thử nghiệm)                    │
    │   ├── Cho phép 3 request thử                 │
    │   ├── Nếu OK → CLOSED ✅                     │
    │   └── Nếu lỗi → OPEN lại 🔴                 │
    │                                              │
    └──────────────────────────────────────────────┘
```

### 3.2. Retry

```
Request → Lỗi → Chờ 500ms → Thử lại → Lỗi → Chờ 500ms → Thử lại → OK ✅
                                                                     hoặc → Fallback 🔴
```

### 3.3. Thứ tự thực hiện

```
Gọi method → Retry → Circuit Breaker → Actual gRPC call
                                              │
                                              ├─ ✅ Success → return
                                              └─ ❌ Fail → Retry thêm
                                                           └─ ❌ Hết lần → Circuit Breaker ghi nhận
                                                                          └─ Fallback method
```

---

## 4. Thêm Thư Viện

Thêm vào `order-service/pom.xml`:

```xml
<!--
    resilience4j-spring-boot3: Thư viện fault tolerance
    Cung cấp:
    - @CircuitBreaker annotation: tự động bọc method với circuit breaker
    - @Retry annotation: tự động thử lại khi method ném exception
    - Auto-configuration: tự đọc config từ application.yml
    - Actuator integration: hiển thị trạng thái circuit breaker trên /actuator/health
-->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.3.0</version>
</dependency>

<!--
    spring-boot-starter-aop: Spring AOP (Aspect-Oriented Programming)
    BẮT BUỘC phải có vì:
    - @CircuitBreaker, @Retry là AOP annotations
    - Spring AOP tạo proxy object bọc quanh method gốc
    - Khi gọi method → proxy intercept → kiểm tra circuit breaker → gọi method gốc
    - Nếu không có AOP → annotations không có tác dụng!
-->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

---

## 5. Tạo Custom Exception

```java
package com.microservice.orderservice.exception;

/**
 * Exception khi service phụ thuộc không khả dụng
 *
 * TẠI SAO cần exception riêng?
 * - RuntimeException("User not found") = user KHÔNG tồn tại (lỗi nghiệp vụ)
 * - ServiceUnavailableException = service CHẾT (lỗi hạ tầng)
 * → Xử lý khác nhau: lỗi nghiệp vụ → reject, lỗi hạ tầng → graceful degradation
 */
public class ServiceUnavailableException extends RuntimeException {

    private final String serviceName;  // Tên service bị lỗi (VD: "user-service")

    public ServiceUnavailableException(String serviceName, String message) {
        super(message);
        this.serviceName = serviceName;
    }

    public ServiceUnavailableException(String serviceName, String message, Throwable cause) {
        super(message, cause);
        this.serviceName = serviceName;
    }

    public String getServiceName() {
        return serviceName;
    }
}
```

---

## 6. Cấu Hình Circuit Breaker + Retry

### Annotation trên method gRPC:

```java
/**
 * @CircuitBreaker:
 *   - name = "userService": tên instance (khớp với config trong YAML)
 *   - fallbackMethod: method được gọi khi circuit mở hoặc exception xảy ra
 *
 * @Retry:
 *   - name = "userService": tên instance retry config
 *   - Tự thử lại trước khi chuyển sang circuit breaker
 */
@CircuitBreaker(name = "userService", fallbackMethod = "checkUserExistsFallback")
@Retry(name = "userService")
public boolean checkUserExists(String userId) {
    // Gọi gRPC bình thường
    // KHÔNG cần try-catch ở đây — Resilience4j xử lý exception
    UserIdRequest request = UserIdRequest.newBuilder()
            .setUserId(userId)
            .build();
    return userStub.checkUserExists(request).getExists();
}
```

> **QUAN TRỌNG**: Bỏ try-catch trong method gốc! Resilience4j cần "thấy" exception để đếm lỗi. Nếu catch hết thì Circuit Breaker không biết có lỗi.

---

## 7. Implement Fallback Methods

### Quy tắc viết Fallback:

```java
// Method GỐC:
public boolean checkUserExists(String userId) { ... }

// Fallback PHẢI có:
// 1. Cùng return type (boolean)
// 2. Cùng tham số (String userId)
// 3. THÊM Throwable ở cuối (nhận exception gốc)
// 4. Có thể private
private boolean checkUserExistsFallback(String userId, Throwable throwable) {
    // Xử lý khi service chết
    log.error("FALLBACK: user-service chết, error: {}", throwable.getMessage());

    // Có 2 lựa chọn:
    // A) Throw ServiceUnavailableException → caller quyết định
    throw new ServiceUnavailableException("user-service", "...", throwable);

    // B) Return giá trị mặc định → chấp nhận rủi ro
    // return true;  // Giả sử user tồn tại
}
```

### Trong project:

| Method | Fallback | Hành vi |
|--------|----------|---------|
| `checkUserExists()` | Throw `ServiceUnavailableException` | OrderService catch → graceful degradation |
| `getUserById()` | Return `null` | Order vẫn trả được, chỉ thiếu userInfo |

---

## 8. Graceful Degradation Trong Business Logic

```java
@Transactional
public OrderDto createOrder(CreateOrderRequest request) {
    boolean userValidated = false;

    try {
        // Gọi gRPC bình thường
        boolean userExists = userGrpcClient.checkUserExists(
            request.getUserId().toString()
        );
        if (!userExists) {
            // User THẬT SỰ không tồn tại → reject
            throw new RuntimeException("User not found: " + request.getUserId());
        }
        userValidated = true;

    } catch (ServiceUnavailableException e) {
        // Service CHẾT → vẫn tạo order (graceful degradation)
        log.warn("⚠️ GRACEFUL DEGRADATION: user-service không khả dụng. "
                + "Tạo order mà KHÔNG validate userId: {}",
                request.getUserId());
    }

    // Tạo order bất kể validate hay không
    Order order = Order.builder()
            .userId(request.getUserId())
            .productName(request.getProductName())
            .quantity(request.getQuantity())
            .totalPrice(request.getTotalPrice())
            .build();

    return orderRepository.save(order);
}
```

---

## 9. Global Exception Handler

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // Service chết → 503 Service Unavailable
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleServiceUnavailable(
            ServiceUnavailableException e) {

        Map<String, Object> body = Map.of(
            "status", 503,
            "error", "Service Unavailable",
            "message", e.getMessage(),
            "service", e.getServiceName()
        );
        return ResponseEntity.status(503).body(body);
    }

    // Lỗi nghiệp vụ → 404 Not Found / 400 Bad Request
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {

        HttpStatus status = e.getMessage().contains("not found")
            ? HttpStatus.NOT_FOUND
            : HttpStatus.BAD_REQUEST;

        Map<String, Object> body = Map.of(
            "status", status.value(),
            "error", status.getReasonPhrase(),
            "message", e.getMessage()
        );
        return ResponseEntity.status(status).body(body);
    }
}
```

---

## 10. Cấu Hình Trong application.yml

```yaml
resilience4j:
  # ═══ Circuit Breaker ═══
  circuitbreaker:
    instances:
      userService:                             # Tên instance (khớp annotation)
        register-health-indicator: true        # Hiện trên /actuator/health
        sliding-window-size: 10                # Theo dõi 10 request gần nhất
        failure-rate-threshold: 50             # Mở circuit khi >= 50% lỗi
        wait-duration-in-open-state:
          seconds: 30                          # OPEN → chờ 30s → HALF_OPEN
        permitted-number-of-calls-in-half-open-state: 3
        minimum-number-of-calls: 5             # Cần >= 5 calls mới tính tỷ lệ lỗi

  # ═══ Retry ═══
  retry:
    instances:
      userService:
        max-attempts: 3                        # Thử tối đa 3 lần
        wait-duration:
          millis: 500                          # Chờ 500ms giữa các lần thử
        retry-exceptions:                      # CHỈ retry với lỗi connection
          - io.grpc.StatusRuntimeException     # gRPC UNAVAILABLE, DEADLINE_EXCEEDED
          - java.util.concurrent.TimeoutException
```

### Giải thích từng config:

| Config | Giá trị | Ý nghĩa |
|--------|---------|---------|
| `sliding-window-size: 10` | 10 | Xem xét 10 request gần nhất để tính tỷ lệ lỗi |
| `failure-rate-threshold: 50` | 50% | Mở circuit khi 5/10 request lỗi |
| `wait-duration-in-open-state: 30s` | 30 giây | Sau 30s ở OPEN → chuyển HALF_OPEN |
| `permitted-number-of-calls-in-half-open-state: 3` | 3 | Cho 3 request thử khi HALF_OPEN |
| `minimum-number-of-calls: 5` | 5 | Phải có ít nhất 5 request mới bắt đầu đánh giá |
| `max-attempts: 3` | 3 lần | Thử 3 lần trước khi fallback |
| `wait-duration: 500ms` | 500ms | Chờ 500ms giữa các lần retry |

---

## 11. Kiểm Tra Hoạt Động

### Test 1: user-service SỐNG

```bash
# Gọi tạo order → thành công, có userInfo
curl -X POST http://localhost:8082/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "...", "productName": "Test", "quantity": 1, "totalPrice": 100}'

# Response: 201 Created
# { "userInfo": { "name": "Phan Kien", ... } }
```

### Test 2: user-service CHẾT

```bash
# Kill user-service (Ctrl+C)

# Gọi tạo order → VẪN thành công, nhưng userInfo = null
curl -X POST http://localhost:8082/api/orders ...

# Response: 201 Created
# { "userInfo": null }  ← graceful degradation

# Console log:
# ⚠️ GRACEFUL DEGRADATION: user-service không khả dụng
```

### Test 3: Circuit Breaker mở

```bash
# Gọi liên tục 5+ lần khi user-service chết
# → Circuit Breaker chuyển sang OPEN
# → Response nhanh hơn (không cần retry, không chờ timeout)

# Kiểm tra trạng thái Circuit Breaker:
curl http://localhost:8082/actuator/health

# {
#   "components": {
#     "circuitBreakers": {
#       "details": {
#         "userService": {
#           "status": "CIRCUIT_OPEN",
#           "state": "OPEN"
#         }
#       }
#     }
#   }
# }
```

### Test 4: Recovery

```bash
# Restart user-service
.\mvnw.cmd -pl user-service spring-boot:run

# Chờ 30s (wait-duration-in-open-state)
# Circuit Breaker → HALF_OPEN → thử request → thành công → CLOSED ✅
# → Hoạt động bình thường trở lại
```

---

## Cấu Trúc File

```
order-service/
├── pom.xml                                    ← Thêm resilience4j, aop
├── src/main/resources/
│   └── application.yml                        ← Thêm resilience4j config
└── src/main/java/.../orderservice/
    ├── exception/
    │   ├── ServiceUnavailableException.java   ← [MỚI] Custom exception
    │   └── GlobalExceptionHandler.java        ← [MỚI] Exception → HTTP response
    ├── grpc/
    │   └── UserGrpcClient.java                ← [SỬA] Thêm @CircuitBreaker, @Retry
    └── service/
        └── OrderService.java                  ← [SỬA] Graceful degradation
```
