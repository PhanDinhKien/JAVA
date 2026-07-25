# Hướng Dẫn Tích Hợp Swagger (OpenAPI) Cho Hệ Thống Microservices

## Mục Lục
1. [Tổng quan Swagger/OpenAPI](#1-tổng-quan-swaggeropenapi)
2. [Kiến trúc Swagger trong Microservices](#2-kiến-trúc-swagger-trong-microservices)
3. [Bước 1: Thêm thư viện springdoc-openapi](#3-bước-1-thêm-thư-viện-springdoc-openapi)
4. [Bước 2: Cấu hình OpenApiConfig cho từng service](#4-bước-2-cấu-hình-openapiconfig-cho-từng-service)
5. [Bước 3: Cấu hình API Gateway proxy cho Swagger](#5-bước-3-cấu-hình-api-gateway-proxy-cho-swagger)
6. [Bước 4: Tạo Aggregated Swagger UI trên Gateway](#6-bước-4-tạo-aggregated-swagger-ui-trên-gateway)
7. [Bước 5: Thêm Swagger Annotations trên Controller](#7-bước-5-thêm-swagger-annotations-trên-controller)
8. [Truy cập Swagger UI](#8-truy-cập-swagger-ui)
9. [Xử lý lỗi thường gặp](#9-xử-lý-lỗi-thường-gặp)

---

## 1. Tổng Quan Swagger/OpenAPI

### Swagger là gì?
- **OpenAPI Specification (OAS)**: Chuẩn mô tả REST API dưới dạng JSON/YAML
- **Swagger UI**: Giao diện web tương tác để xem và test API
- **springdoc-openapi**: Thư viện Java tự động sinh OpenAPI spec từ Spring Boot controllers

### Tại sao cần Swagger trong Microservices?
- 📋 **Documentation**: Tự động tạo tài liệu API từ code
- 🧪 **Testing**: Test API trực tiếp trên trình duyệt
- 🔍 **Discoverability**: Biết được mỗi service có API gì
- 🎯 **Aggregation**: Gom tất cả API từ nhiều service vào 1 trang duy nhất

---

## 2. Kiến Trúc Swagger Trong Microservices

```
Trình duyệt
    │
    │ http://localhost:8080/swagger-ui.html
    │
    ▼
┌──────────────────────────────────────────────┐
│              API Gateway (port 8080)         │
│                                              │
│  swagger-ui.html (static file)               │
│  ├── Dropdown: "User Service"                │
│  │   └── Fetch: /user-service/v3/api-docs    │
│  └── Dropdown: "Order Service"               │
│      └── Fetch: /order-service/v3/api-docs   │
│                                              │
│  Routes (proxy):                             │
│  /user-service/v3/api-docs → user-service    │
│  /order-service/v3/api-docs → order-service  │
└──────────┬─────────────────────┬─────────────┘
           │                     │
    ┌──────▼──────┐       ┌──────▼──────┐
    │ user-service│       │order-service│
    │  port 8081  │       │  port 8082  │
    │             │       │             │
    │ /v3/api-docs│       │ /v3/api-docs│
    │ (JSON spec) │       │ (JSON spec) │
    └─────────────┘       └─────────────┘
```

### Luồng hoạt động:
1. Truy cập `http://localhost:8080/swagger-ui.html` → Gateway trả file HTML tĩnh
2. Swagger UI JS tải `/user-service/v3/api-docs` → Gateway proxy đến user-service
3. user-service trả JSON spec (danh sách endpoints, params, responses)
4. Swagger UI render thành giao diện tương tác
5. Khi "Try it out" → request gửi qua Gateway → route đến service đúng

---

## 3. Bước 1: Thêm Thư Viện springdoc-openapi

### Thêm vào `pom.xml` của **mỗi service** (user-service, order-service):

```xml
<!--
    springdoc-openapi-starter-webmvc-ui
    Mục đích: Tự động tạo OpenAPI specification từ Spring MVC controllers

    Cung cấp:
    1. /v3/api-docs      → JSON specification của tất cả API
    2. /swagger-ui.html  → Swagger UI giao diện web (cho mỗi service riêng)

    Cách hoạt động:
    - Quét tất cả @RestController, @RequestMapping
    - Đọc @GetMapping, @PostMapping, @PutMapping, @DeleteMapping
    - Sinh ra OpenAPI JSON mô tả: URL, method, params, request body, response
    - Render Swagger UI bằng WebJar (đi kèm trong dependency)

    Lưu ý:
    - Dùng cho Spring MVC (WebMVC) — user-service, order-service
    - KHÔNG dùng cho Spring WebFlux (api-gateway) — xem mục 6
-->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.8.8</version>
</dependency>
```

> **⚠️ LƯU Ý**: API Gateway dùng Spring WebFlux (reactive), KHÔNG thêm thư viện này. Gateway chỉ proxy và host file HTML tĩnh.

### Tóm tắt dependency:

| Service | Thư viện | Lý do |
|---------|----------|-------|
| user-service | `springdoc-openapi-starter-webmvc-ui` | Spring MVC → sinh API docs |
| order-service | `springdoc-openapi-starter-webmvc-ui` | Spring MVC → sinh API docs |
| api-gateway | ❌ Không thêm | WebFlux, chỉ proxy + host static HTML |

---

## 4. Bước 2: Cấu Hình OpenApiConfig Cho Từng Service

### 4.1. User Service - `OpenApiConfig.java`

```java
package com.microservice.userservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                /*
                 * Server URL = "/"
                 * QUAN TRỌNG khi dùng qua API Gateway!
                 *
                 * Nếu không set → mặc định là http://localhost:8081
                 * → Swagger UI gọi trực tiếp đến 8081 → CORS error!
                 *
                 * Set "/" → Swagger UI gọi tương đối qua Gateway (port 8080)
                 * → Gateway proxy đến đúng service → OK!
                 */
                .addServersItem(new Server()
                        .url("/")
                        .description("Default Server"))

                /*
                 * Info: Thông tin mô tả API
                 * Hiển thị trên đầu trang Swagger UI
                 */
                .info(new Info()
                        .title("User Service API")
                        .description("API quản lý người dùng và xác thực JWT")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Phan Kien")
                                .email("phankien@example.com")))

                /*
                 * Security: Cấu hình JWT authentication
                 * Hiển thị nút "Authorize 🔒" trên Swagger UI
                 * → Nhập token → tự động thêm header Authorization: Bearer {token}
                 */
                .addSecurityItem(new SecurityRequirement()
                        .addList("Bearer Authentication"))
                .components(new Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .name("Bearer Authentication")
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Nhập JWT token")));
    }
}
```

### 4.2. Order Service - `OpenApiConfig.java`

```java
package com.microservice.orderservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                // Server URL "/" → Swagger gọi qua Gateway (xem giải thích ở trên)
                .addServersItem(new Server()
                        .url("/")
                        .description("Default Server"))
                .info(new Info()
                        .title("Order Service API")
                        .description("API quản lý đơn hàng")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Phan Kien")
                                .email("phankien@example.com")));

        // Lưu ý: Order service không cần security scheme
        // vì JWT auth sẽ được xử lý ở gateway level
    }
}
```

### Giải thích các thành phần OpenAPI:

| Thành phần | Method | Mục đích |
|------------|--------|----------|
| **Server** | `.addServersItem()` | Base URL cho API calls. Set `"/"` để dùng qua proxy |
| **Info** | `.info()` | Tiêu đề, mô tả, version hiển thị trên Swagger UI |
| **Contact** | `.contact()` | Thông tin liên hệ developer |
| **Security** | `.addSecurityItem()` | Thêm nút "Authorize" cho JWT token |
| **SecurityScheme** | `.addSecuritySchemes()` | Cấu hình loại auth (Bearer/OAuth2/ApiKey) |

---

## 5. Bước 3: Cấu Hình API Gateway Proxy Cho Swagger

### Tại sao cần proxy?

```
Không có proxy:
  Browser → http://localhost:8080/swagger-ui.html
  Swagger UI cần JSON spec → gọi http://localhost:8081/v3/api-docs
  → CORS ERROR! (cross-origin: 8080 → 8081)

Có proxy:
  Browser → http://localhost:8080/swagger-ui.html
  Swagger UI → gọi /user-service/v3/api-docs (cùng origin 8080)
  → Gateway proxy → http://user-service:8081/v3/api-docs
  → OK! ✅
```

### Cấu hình routes trong `api-gateway/application.yml`:

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            # ════════════════════════════════
            # Business API routes (đã có)
            # ════════════════════════════════
            - id: user-service
              uri: lb://user-service
              predicates:
                - Path=/api/users/**
            - id: order-service
              uri: lb://order-service
              predicates:
                - Path=/api/orders/**

            # ════════════════════════════════
            # Swagger proxy routes
            # ════════════════════════════════

            # --- user-service Swagger ---

            # Route 1: Proxy Swagger UI assets
            # /user-service/swagger-ui/** → /swagger-ui/**
            - id: user-service-swagger-ui
              uri: lb://user-service
              predicates:
                - Path=/user-service/swagger-ui/**
              filters:
                # RewritePath: Xóa prefix "/user-service" trước khi forward
                # VD: /user-service/swagger-ui/index.html → /swagger-ui/index.html
                - RewritePath=/user-service(?<segment>.*), ${segment}

            # Route 2: Proxy API docs JSON
            # /user-service/v3/api-docs → /v3/api-docs
            - id: user-service-api-docs
              uri: lb://user-service
              predicates:
                - Path=/user-service/v3/api-docs/**
              filters:
                - RewritePath=/user-service(?<segment>.*), ${segment}

            # --- order-service Swagger ---
            - id: order-service-swagger-ui
              uri: lb://order-service
              predicates:
                - Path=/order-service/swagger-ui/**
              filters:
                - RewritePath=/order-service(?<segment>.*), ${segment}

            - id: order-service-api-docs
              uri: lb://order-service
              predicates:
                - Path=/order-service/v3/api-docs/**
              filters:
                - RewritePath=/order-service(?<segment>.*), ${segment}
```

### Giải thích RewritePath:

```
Trình duyệt request:  /user-service/v3/api-docs
                        ↓
Predicate match:       Path=/user-service/v3/api-docs/**  ✅
                        ↓
RewritePath filter:    /user-service(/v3/api-docs) → /v3/api-docs
                        ↓
Forward to service:    lb://user-service/v3/api-docs
                        ↓
user-service xử lý:    GET /v3/api-docs → trả JSON spec
```

---

## 6. Bước 4: Tạo Aggregated Swagger UI Trên Gateway

### Tại sao cần Aggregated UI?

| Cách | URL | Nhược điểm |
|------|-----|------------|
| Truy cập từng service | `localhost:8081/swagger-ui.html` | Phải nhớ port, CORS issues |
| ✅ Aggregated UI | `localhost:8080/swagger-ui.html` | 1 URL, dropdown chọn service |

### Tạo file `api-gateway/src/main/resources/static/swagger-ui.html`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>API Gateway - Swagger UI</title>

    <!--
        swagger-ui-dist: Swagger UI library (từ CDN)
        CSS: giao diện Swagger UI
    -->
    <link rel="stylesheet"
          href="https://unpkg.com/swagger-ui-dist@5.18.2/swagger-ui.css">
    <style>
        body { margin: 0; background: #fafafa; }
    </style>
</head>
<body>
    <!-- Container cho Swagger UI render vào -->
    <div id="swagger-ui"></div>

    <!--
        swagger-ui-bundle.js: Core Swagger UI logic
        swagger-ui-standalone-preset.js: Thêm top bar + dropdown selector
    -->
    <script src="https://unpkg.com/swagger-ui-dist@5.18.2/swagger-ui-bundle.js"></script>
    <script src="https://unpkg.com/swagger-ui-dist@5.18.2/swagger-ui-standalone-preset.js"></script>

    <script>
        window.onload = function () {
            SwaggerUIBundle({
                /*
                 * urls: Danh sách các API spec URLs
                 * → Tạo dropdown menu trên Swagger UI
                 * → Mỗi URL trỏ đến /v3/api-docs của service (qua proxy)
                 *
                 * Khi chọn "User Service" → fetch /user-service/v3/api-docs
                 * → Gateway proxy → user-service:8081/v3/api-docs
                 */
                urls: [
                    {
                        url: "/user-service/v3/api-docs",
                        name: "User Service"
                    },
                    {
                        url: "/order-service/v3/api-docs",
                        name: "Order Service"
                    }
                    // Thêm service mới? Chỉ cần thêm 1 dòng ở đây:
                    // { url: "/product-service/v3/api-docs", name: "Product Service" }
                ],

                // Render vào div#swagger-ui
                dom_id: '#swagger-ui',

                // Deep linking: URL tự thay đổi khi click vào endpoint
                deepLinking: true,

                // Presets: bộ plugin Swagger UI
                presets: [
                    SwaggerUIBundle.presets.apis,
                    SwaggerUIStandalonePreset    // ← Thêm top bar + dropdown
                ],

                plugins: [
                    SwaggerUIBundle.plugins.DownloadUrl
                ],

                /*
                 * layout: "StandaloneLayout"
                 * → Hiện top bar với dropdown chọn service
                 *
                 * ⚠️ LƯU Ý: Phải dùng SwaggerUIStandalonePreset (biến GLOBAL)
                 * KHÔNG PHẢI SwaggerUIBundle.SwaggerUIStandalonePreset
                 */
                layout: "StandaloneLayout"
            });
        };
    </script>
</body>
</html>
```

### Giải thích từng config:

| Config | Giá trị | Mục đích |
|--------|---------|----------|
| `urls` | Array | Danh sách service APIs → tạo dropdown menu |
| `url` | `/user-service/v3/api-docs` | Đường dẫn tương đối (qua Gateway proxy) |
| `name` | `"User Service"` | Tên hiển thị trong dropdown |
| `dom_id` | `'#swagger-ui'` | DOM element để render UI |
| `deepLinking` | `true` | URL cập nhật khi chọn endpoint |
| `SwaggerUIStandalonePreset` | Biến global | Preset cung cấp top bar + dropdown |
| `layout` | `"StandaloneLayout"` | Bật layout có top bar |

### Thêm service mới vào Swagger

Khi thêm service mới (VD: product-service), chỉ cần:

1. Thêm route proxy trong `application.yml`:
```yaml
- id: product-service-api-docs
  uri: lb://product-service
  predicates:
    - Path=/product-service/v3/api-docs/**
  filters:
    - RewritePath=/product-service(?<segment>.*), ${segment}
```

2. Thêm URL vào `swagger-ui.html`:
```javascript
urls: [
    { url: "/user-service/v3/api-docs", name: "User Service" },
    { url: "/order-service/v3/api-docs", name: "Order Service" },
    { url: "/product-service/v3/api-docs", name: "Product Service" }  // MỚI
]
```

---

## 7. Bước 5: Thêm Swagger Annotations Trên Controller (Tùy chọn)

### Các annotation mô tả API chi tiết hơn

Thêm annotation từ package `io.swagger.v3.oas.annotations` vào controller:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/*
 * @Tag: Nhóm các endpoints lại
 * Hiển thị trên Swagger UI dưới dạng section
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "API quản lý đơn hàng")
public class OrderController {

    /*
     * @Operation: Mô tả chi tiết cho endpoint
     * - summary: Tóm tắt ngắn (hiện trên dòng endpoint)
     * - description: Mô tả dài (hiện khi mở rộng)
     *
     * @ApiResponses: Liệt kê các response có thể trả về
     * - 201: Created thành công
     * - 400: Request body sai
     * - 503: user-service không khả dụng
     */
    @PostMapping
    @Operation(
        summary = "Tạo đơn hàng mới",
        description = "Tạo order mới. Internally gọi gRPC đến user-service để validate userId."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Tạo order thành công"),
        @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ"),
        @ApiResponse(responseCode = "404", description = "User không tồn tại"),
        @ApiResponse(responseCode = "503", description = "User service không khả dụng")
    })
    public ResponseEntity<OrderDto> createOrder(
            @Valid @RequestBody CreateOrderRequest request) {
        OrderDto order = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Lấy đơn hàng theo ID")
    public ResponseEntity<OrderDto> getOrderById(
            /*
             * @Parameter: Mô tả cho từng tham số
             * Hiện trên Swagger UI trong phần Parameters
             */
            @Parameter(description = "UUID của đơn hàng", required = true)
            @PathVariable UUID id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    @GetMapping(params = "userId")
    @Operation(summary = "Lấy đơn hàng theo userId")
    public ResponseEntity<List<OrderDto>> getOrdersByUserId(
            @Parameter(description = "UUID của user")
            @RequestParam UUID userId) {
        return ResponseEntity.ok(orderService.getOrdersByUserId(userId));
    }

    @GetMapping
    @Operation(summary = "Lấy tất cả đơn hàng")
    public ResponseEntity<List<OrderDto>> getAllOrders() {
        return ResponseEntity.ok(orderService.getAllOrders());
    }
}
```

### Tóm tắt Swagger Annotations:

| Annotation | Đặt ở | Mục đích |
|------------|-------|----------|
| `@Tag` | Class | Nhóm endpoints thành section |
| `@Operation` | Method | Mô tả tóm tắt + chi tiết cho endpoint |
| `@ApiResponse` | Method | Liệt kê HTTP response codes |
| `@ApiResponses` | Method | Gom nhiều `@ApiResponse` |
| `@Parameter` | Param | Mô tả cho path variable / query param |
| `@Schema` | DTO field | Mô tả field trong request/response body |

### Annotation trên DTO (tùy chọn):

```java
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@Builder
public class CreateOrderRequest {

    @Schema(description = "UUID của user đặt hàng",
            example = "caee30a8-98ed-49b0-88f4-8d36e47bc76c")
    private UUID userId;

    @Schema(description = "Tên sản phẩm", example = "Máy Khoan")
    private String productName;

    @Schema(description = "Số lượng", example = "2", minimum = "1")
    private Integer quantity;

    @Schema(description = "Tổng giá (VNĐ)", example = "1500000")
    private BigDecimal totalPrice;
}
```

---

## 8. Truy Cập Swagger UI

### Aggregated (qua Gateway) — Khuyến nghị

| URL | Mô tả |
|-----|-------|
| **http://localhost:8080/swagger-ui.html** | Swagger UI tổng hợp (dropdown chọn service) |
| http://localhost:8080/user-service/v3/api-docs | JSON spec user-service (qua proxy) |
| http://localhost:8080/order-service/v3/api-docs | JSON spec order-service (qua proxy) |

### Trực tiếp từng service (cho dev/debug)

| URL | Mô tả |
|-----|-------|
| http://localhost:8081/swagger-ui.html | Swagger UI user-service |
| http://localhost:8082/swagger-ui.html | Swagger UI order-service |
| http://localhost:8081/v3/api-docs | JSON spec user-service |
| http://localhost:8082/v3/api-docs | JSON spec order-service |

---

## 9. Xử Lý Lỗi Thường Gặp

### Lỗi 1: "No layout defined for StandaloneLayout"

**Nguyên nhân**: Dùng `SwaggerUIBundle.SwaggerUIStandalonePreset` thay vì `SwaggerUIStandalonePreset`

**Sửa**:
```javascript
// ❌ SAI
presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset]

// ✅ ĐÚNG (SwaggerUIStandalonePreset là biến GLOBAL)
presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset]
```

### Lỗi 2: CORS Error khi gọi API từ Swagger UI

**Nguyên nhân**: Swagger UI gọi trực tiếp đến service IP (VD: `http://10.10.10.33:8082`)

**Sửa**: Thêm `Server URL = "/"` trong OpenApiConfig:
```java
.addServersItem(new Server().url("/"))
```
→ Swagger UI gọi tương đối qua Gateway → không CORS.

### Lỗi 3: 404 Not Found khi truy cập `/swagger-ui/index.html`

**Nguyên nhân**: Gateway không có springdoc dependency → không có Swagger UI

**Sửa**: Gateway chỉ host static HTML, truy cập `/swagger-ui.html` (không phải `/swagger-ui/index.html`)

### Lỗi 4: Swagger UI hiện nhưng không có APIs

**Nguyên nhân**: API docs URL sai hoặc route proxy chưa cấu hình

**Kiểm tra**:
```bash
# Kiểm tra JSON spec có trả về không
curl http://localhost:8080/user-service/v3/api-docs

# Nếu 404 → kiểm tra route proxy trong application.yml
# Nếu JSON trống → kiểm tra service có đang chạy không
```

### Lỗi 5: Dropdown không có service nào

**Nguyên nhân**: Sai property `urls` trong swagger-ui.html

**Kiểm tra**: Mở DevTools (F12) → Network tab → xem request đến `/v3/api-docs` có thành công không

---

## Cấu Trúc File Tổng Hợp

```
project/
├── api-gateway/
│   ├── pom.xml                        ← KHÔNG có springdoc dependency
│   └── src/main/resources/
│       ├── application.yml            ← Routes proxy cho swagger
│       └── static/
│           └── swagger-ui.html        ← [MỚI] Aggregated Swagger UI
│
├── user-service/
│   ├── pom.xml                        ← springdoc-openapi-starter-webmvc-ui
│   └── src/main/java/.../config/
│       └── OpenApiConfig.java         ← [MỚI] Info + Security + Server "/"
│
└── order-service/
    ├── pom.xml                        ← springdoc-openapi-starter-webmvc-ui
    └── src/main/java/.../config/
        └── OpenApiConfig.java         ← [MỚI] Info + Server "/"
```
