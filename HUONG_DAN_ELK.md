# Hướng Dẫn Tích Hợp ELK Stack (Elasticsearch, Logstash, Kibana)

## Mục Lục
1. [Tổng quan](#1-tổng-quan)
2. [Kiến trúc hệ thống](#2-kiến-trúc-hệ-thống)
3. [Thiết lập ELK Stack bằng Docker](#3-thiết-lập-elk-stack-bằng-docker)
4. [Thêm thư viện vào các Microservice](#4-thêm-thư-viện-vào-các-microservice)
5. [Cấu hình Logback cho từng service](#5-cấu-hình-logback-cho-từng-service)
6. [Khởi chạy hệ thống](#6-khởi-chạy-hệ-thống)
7. [Sử dụng Kibana để xem logs](#7-sử-dụng-kibana-để-xem-logs)
8. [Các câu query hữu ích trên Kibana](#8-các-câu-query-hữu-ích-trên-kibana)

---

## 1. Tổng Quan

### ELK Stack là gì?
ELK Stack gồm 3 thành phần chính:

| Thành phần | Vai trò | Port |
|------------|---------|------|
| **Elasticsearch** | Database lưu trữ và tìm kiếm logs | 9200 |
| **Logstash** | Nhận, xử lý, chuyển tiếp logs | 5044 |
| **Kibana** | Giao diện web để xem và phân tích logs | 5601 |

### Tại sao cần ELK trong Microservices?
- **Centralized Logging**: Tất cả logs từ user-service, order-service, api-gateway đổ về 1 nơi
- **Dễ debug**: Tìm kiếm logs theo service, level, message, thời gian
- **Trace request**: Theo dõi 1 request đi qua nhiều service (traceId)
- **Real-time**: Logs xuất hiện ngay lập tức trên Kibana

---

## 2. Kiến Trúc Hệ Thống

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ user-service│     │order-service│     │ api-gateway │
│  (port 8081)│     │ (port 8082) │     │ (port 8080) │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       │   JSON logs qua TCP port 5044         │
       └───────────────────┼───────────────────┘
                           │
                    ┌──────▼──────┐
                    │  Logstash   │  ← Nhận logs, parse, chuyển tiếp
                    │ (port 5044) │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │Elasticsearch│  ← Lưu trữ & index logs
                    │ (port 9200) │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │   Kibana    │  ← UI xem logs
                    │ (port 5601) │
                    └─────────────┘
```

**Luồng hoạt động:**
1. Mỗi microservice viết log bình thường (`log.info()`, `log.error()`, ...)
2. **Logback** (thư viện logging mặc định của Spring Boot) bắt log và gửi dạng **JSON qua TCP** đến Logstash
3. **Logstash** nhận JSON, xử lý, rồi gửi đến **Elasticsearch** để lưu trữ
4. **Kibana** đọc dữ liệu từ Elasticsearch và hiển thị lên giao diện web

---

## 3. Thiết Lập ELK Stack Bằng Docker

### 3.1. File `docker/docker-compose.yml`

Thêm 3 services (elasticsearch, logstash, kibana) vào docker-compose:

```yaml
services:
  # ... (postgres đã có sẵn) ...

  # ==================== ELK Stack ====================

  # Elasticsearch - Database lưu trữ logs
  # Tìm kiếm full-text cực nhanh, hỗ trợ indexing theo ngày
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
    container_name: microservice-elasticsearch
    restart: unless-stopped
    environment:
      # Chạy single-node (không cần cluster cho dev)
      - discovery.type=single-node
      # Tắt bảo mật (chỉ dùng cho development)
      - xpack.security.enabled=false
      - xpack.security.enrollment.enabled=false
      # Giới hạn RAM cho Elasticsearch (tránh ngốn hết RAM máy)
      - ES_JAVA_OPTS=-Xms512m -Xmx512m
    ports:
      - "9200:9200"    # REST API endpoint
    volumes:
      # Persist data khi restart container
      - elasticsearch_data:/usr/share/elasticsearch/data
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:9200/_cluster/health || exit 1"]
      interval: 15s
      timeout: 10s
      retries: 5

  # Logstash - Pipeline xử lý logs
  # Nhận logs từ các service qua TCP, parse JSON, gửi đến Elasticsearch
  logstash:
    image: docker.elastic.co/logstash/logstash:8.17.0
    container_name: microservice-logstash
    restart: unless-stopped
    volumes:
      # Mount file cấu hình pipeline
      - ./logstash/logstash.conf:/usr/share/logstash/pipeline/logstash.conf:ro
    ports:
      - "5044:5044"    # TCP input - nơi nhận logs từ các service
    environment:
      - LS_JAVA_OPTS=-Xms256m -Xmx256m
    depends_on:
      elasticsearch:
        condition: service_healthy    # Chờ Elasticsearch sẵn sàng mới start

  # Kibana - Giao diện web xem logs
  # Truy cập http://localhost:5601 để search, filter, visualize logs
  kibana:
    image: docker.elastic.co/kibana/kibana:8.17.0
    container_name: microservice-kibana
    restart: unless-stopped
    environment:
      - ELASTICSEARCH_HOSTS=http://elasticsearch:9200
    ports:
      - "5601:5601"    # Web UI
    depends_on:
      elasticsearch:
        condition: service_healthy

volumes:
  elasticsearch_data:
    driver: local
```

### 3.2. File `docker/logstash/logstash.conf`

Đây là file cấu hình **pipeline** của Logstash — quy định cách nhận, xử lý, và gửi logs:

```conf
# INPUT: Cách Logstash nhận logs
input {
  tcp {
    port => 5044              # Lắng nghe trên port 5044
    codec => json_lines       # Parse mỗi dòng là 1 JSON object
  }
}

# FILTER: Xử lý/biến đổi logs trước khi lưu
filter {
  # Thêm timestamp nếu chưa có
  if ![timestamp] {
    mutate {
      add_field => { "timestamp" => "%{@timestamp}" }
    }
  }

  # Copy service_name thành field "service" để dễ filter
  if [service_name] {
    mutate {
      add_field => { "service" => "%{service_name}" }
    }
  }
}

# OUTPUT: Nơi Logstash gửi logs đến
output {
  # Gửi đến Elasticsearch, index theo ngày
  # VD: microservice-logs-2026.07.26
  elasticsearch {
    hosts => ["http://elasticsearch:9200"]
    index => "microservice-logs-%{+YYYY.MM.dd}"
  }

  # In ra stdout để debug Logstash (xem trong docker logs)
  stdout {
    codec => rubydebug
  }
}
```

---

## 4. Thêm Thư Viện Vào Các Microservice

### 4.1. Thư viện cần thêm

| Thư viện | GroupId | ArtifactId | Version | Mục đích |
|----------|---------|------------|---------|----------|
| **Logstash Logback Encoder** | `net.logstash.logback` | `logstash-logback-encoder` | `8.1` | Encode logs thành JSON format và gửi qua TCP đến Logstash |

> **Giải thích**: Spring Boot mặc định dùng **Logback** làm logging framework. Thư viện `logstash-logback-encoder` mở rộng Logback bằng cách thêm:
> - **LogstashEncoder**: Chuyển log message thành JSON có cấu trúc (structured logging)
> - **LogstashTcpSocketAppender**: Gửi JSON logs qua kết nối TCP đến Logstash

### 4.2. Thêm dependency vào `pom.xml`

Thêm đoạn sau vào `<dependencies>` trong `pom.xml` của **mỗi service** (user-service, order-service, api-gateway):

```xml
<!-- Logstash Logback Encoder (ELK)
     Mục đích: Chuyển đổi logs thành JSON format và gửi đến Logstash qua TCP
     - LogstashEncoder: serialize log events thành JSON
     - LogstashTcpSocketAppender: mở kết nối TCP đến Logstash
     - Tự động reconnect nếu Logstash chưa sẵn sàng
-->
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>8.1</version>
</dependency>
```

### Các file pom.xml đã được cập nhật:
- `user-service/pom.xml`
- `order-service/pom.xml`
- `api-gateway/pom.xml`

---

## 5. Cấu Hình Logback Cho Từng Service

### 5.1. Tạo file `logback-spring.xml`

Tạo file `src/main/resources/logback-spring.xml` trong **mỗi service**. File này thay thế cấu hình logging mặc định của Spring Boot:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!--
        springProperty: Đọc giá trị từ application.yml
        Ở đây lấy spring.application.name để biết log đến từ service nào
    -->
    <springProperty scope="context" name="appName"
                    source="spring.application.name"
                    defaultValue="unknown-service"/>

    <!--
        CONSOLE Appender: Giữ nguyên log trên console (terminal)
        Pattern giải thích:
        - %d{...}: Timestamp
        - %-5level: Log level (INFO, ERROR, ...) căn trái 5 ký tự
        - [%thread]: Tên thread xử lý
        - %logger{36}: Tên class (rút gọn 36 ký tự)
        - %msg: Nội dung log message
        - %n: Xuống dòng
    -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %highlight(%-5level) [%thread] %cyan(%logger{36}) - %msg%n</pattern>
        </encoder>
    </appender>

    <!--
        LOGSTASH Appender: Gửi logs đến Logstash qua TCP
        - LogstashTcpSocketAppender: Mở kết nối TCP đến Logstash
        - LogstashEncoder: Chuyển log thành JSON format, ví dụ:
          {
            "@timestamp": "2026-07-26T00:25:00.000+07:00",
            "level": "INFO",
            "logger_name": "c.m.userservice.controller.UserController",
            "message": "Get user by id: abc-123",
            "service_name": "user-service",
            "thread_name": "http-nio-8081-exec-1"
          }
    -->
    <appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
        <!-- Địa chỉ Logstash (localhost vì service chạy ngoài Docker) -->
        <destination>localhost:5044</destination>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <!-- Thêm field tùy chỉnh: tên service -->
            <customFields>{"service_name":"${appName}"}</customFields>
            <!-- Bao gồm traceId/spanId nếu có (distributed tracing) -->
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
        </encoder>
        <!-- Tự động reconnect sau 5 giây nếu mất kết nối -->
        <reconnectionDelay>5 seconds</reconnectionDelay>
        <!-- Giữ kết nối TCP alive -->
        <keepAliveDuration>5 minutes</keepAliveDuration>
    </appender>

    <!-- Root Logger: Bắt tất cả logs từ level INFO trở lên -->
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>    <!-- Gửi đến console -->
        <appender-ref ref="LOGSTASH"/>   <!-- Gửi đến Logstash -->
    </root>

    <!-- Giảm noise từ framework (chỉ hiện WARN trở lên) -->
    <logger name="org.springframework" level="WARN"/>
    <logger name="org.hibernate" level="WARN"/>

    <!--
        App logs ở level DEBUG: Hiện tất cả log từ code của mình
        Đổi package name tương ứng với mỗi service:
        - user-service:  com.microservice.userservice
        - order-service: com.microservice.orderservice
        - api-gateway:   com.microservice.gateway
    -->
    <logger name="com.microservice.userservice" level="DEBUG"/>
</configuration>
```

### 5.2. Khác biệt giữa các service

Chỉ khác 2 chỗ giữa các service:

| Service | `defaultValue` | Logger package |
|---------|---------------|----------------|
| user-service | `"user-service"` | `com.microservice.userservice` |
| order-service | `"order-service"` | `com.microservice.orderservice` |
| api-gateway | `"api-gateway"` | `com.microservice.gateway` |

### Các file đã được tạo:
- `user-service/src/main/resources/logback-spring.xml`
- `order-service/src/main/resources/logback-spring.xml`
- `api-gateway/src/main/resources/logback-spring.xml`

---

## 6. Khởi Chạy Hệ Thống

### Bước 1: Khởi động ELK Stack

```bash
cd docker
docker-compose up -d elasticsearch logstash kibana
```

Chờ đợi: Lần đầu tiên Docker sẽ tải images (~1.5GB). Kiểm tra trạng thái:

```bash
# Kiểm tra containers đang chạy
docker ps

# Kiểm tra Elasticsearch đã sẵn sàng
curl http://localhost:9200

# Xem logs của Logstash (nếu cần debug)
docker logs microservice-logstash -f
```

### Bước 2: Khởi động các Microservice

```bash
# Từ thư mục gốc project
.\mvnw.cmd -pl discovery-server spring-boot:run
.\mvnw.cmd -pl user-service spring-boot:run
.\mvnw.cmd -pl order-service spring-boot:run
.\mvnw.cmd -pl api-gateway spring-boot:run
```

> **Lưu ý**: Nếu Logstash chưa sẵn sàng khi service khởi động, bạn sẽ thấy warning trong console. Không sao — `LogstashTcpSocketAppender` sẽ tự động reconnect sau 5 giây.

### Bước 3: Truy cập Kibana

Mở trình duyệt: **http://localhost:5601**

---

## 7. Sử Dụng Kibana Để Xem Logs

### 7.1. Tạo Data View (lần đầu)

1. Truy cập **http://localhost:5601**
2. Vào menu **☰ → Discover**
3. Click **"Create data view"**
4. Nhập:
   - **Name**: `Microservice Logs`
   - **Index pattern**: `microservice-logs-*`
   - **Timestamp field**: `@timestamp`
5. Click **"Save data view"**

### 7.2. Xem Logs

Sau khi tạo Data View, bạn sẽ thấy trang **Discover** với các logs real-time.

**Các cột quan trọng cần thêm** (click "+ Add field"):
- `service_name` — Tên service (user-service, order-service, api-gateway)
- `level` — Log level (INFO, ERROR, WARN, DEBUG)
- `message` — Nội dung log
- `logger_name` — Class phát sinh log
- `thread_name` — Thread xử lý
- `stack_trace` — Stack trace (nếu có lỗi)

### 7.3. Thử nghiệm

1. Gọi API qua Swagger UI: `http://localhost:8080/swagger-ui.html`
2. Quay lại Kibana → Click **"Refresh"** → Logs xuất hiện!

---

## 8. Các Câu Query Hữu Ích Trên Kibana

Nhập vào thanh search trên Kibana (KQL syntax):

```
# Xem tất cả logs của order-service
service_name: "order-service"

# Xem chỉ logs lỗi
level: "ERROR"

# Xem logs lỗi của user-service
service_name: "user-service" AND level: "ERROR"

# Tìm log chứa từ khóa
message: "gRPC" AND service_name: "order-service"

# Tìm log của 1 request cụ thể (theo userId)
message: "*caee30a8*"

# Xem logs của gRPC communication
message: "gRPC*"

# Xem tất cả exceptions
stack_trace: *

# Xem logs trong 15 phút gần nhất (chỉnh time picker ở góc phải)
# → Click vào time picker → chọn "Last 15 minutes"
```

---

## Cấu Trúc File Sau Khi Tích Hợp

```
project/
├── docker/
│   ├── docker-compose.yml          ← Thêm elasticsearch, logstash, kibana
│   ├── logstash/
│   │   └── logstash.conf           ← [MỚI] Pipeline config cho Logstash
│   ├── .env
│   └── init-db.sql
├── user-service/
│   ├── pom.xml                     ← Thêm logstash-logback-encoder
│   └── src/main/resources/
│       ├── application.yml
│       └── logback-spring.xml      ← [MỚI] Logback config
├── order-service/
│   ├── pom.xml                     ← Thêm logstash-logback-encoder
│   └── src/main/resources/
│       ├── application.yml
│       └── logback-spring.xml      ← [MỚI] Logback config
└── api-gateway/
    ├── pom.xml                     ← Thêm logstash-logback-encoder
    └── src/main/resources/
        ├── application.yml
        └── logback-spring.xml      ← [MỚI] Logback config
```

---

## Troubleshooting

| Vấn đề | Nguyên nhân | Giải pháp |
|---------|-------------|-----------|
| Console hiện warning `Connection refused` | Logstash chưa start xong | Chờ Logstash ready, appender sẽ tự reconnect |
| Kibana không thấy logs | Chưa tạo Data View | Tạo Data View với pattern `microservice-logs-*` |
| Elasticsearch không start | Thiếu RAM | Giảm `ES_JAVA_OPTS` xuống `-Xms256m -Xmx256m` |
| Docker pull rất chậm | Mạng chậm | Dùng Docker mirror hoặc chờ |
