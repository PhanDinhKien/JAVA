# Hướng Dẫn Tích Hợp Prometheus + Grafana — Monitoring & Metrics

## 📋 Mục Lục

1. [Tổng Quan Kiến Trúc](#1-tổng-quan-kiến-trúc)
2. [Các Thành Phần](#2-các-thành-phần)
3. [Cấu Hình Chi Tiết](#3-cấu-hình-chi-tiết)
4. [Khởi Động & Truy Cập](#4-khởi-động--truy-cập)
5. [Giải Thích Metrics](#5-giải-thích-metrics)
6. [Grafana Dashboard](#6-grafana-dashboard)
7. [Xử Lý Sự Cố](#7-xử-lý-sự-cố)

---

## 1. Tổng Quan Kiến Trúc

```
┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐
│  user-service   │   │  order-service  │   │   api-gateway   │
│    :8081        │   │    :8082        │   │    :8080        │
│                 │   │                 │   │                 │
│ /actuator/      │   │ /actuator/      │   │ /actuator/      │
│  prometheus     │   │  prometheus     │   │  prometheus     │
└────────┬────────┘   └────────┬────────┘   └────────┬────────┘
         │                     │                     │
         └─────────────────────┼─────────────────────┘
                               │
                        scrape mỗi 10s
                               │
                    ┌──────────▼──────────┐
                    │     PROMETHEUS      │
                    │   Container :9090   │
                    │   Host      :9093   │
                    │                     │
                    │ • Lưu trữ metrics   │
                    │ • PromQL queries    │
                    │ • Alert rules       │
                    └──────────┬──────────┘
                               │
                          datasource
                               │
                    ┌──────────▼──────────┐
                    │      GRAFANA        │
                    │   Container :3000   │
                    │   Host      :3000   │
                    │                     │
                    │ • Dashboard UI      │
                    │ • Visualization     │
                    │ • Alerting          │
                    └─────────────────────┘
```

### Luồng hoạt động:

1. **Spring Boot** expose metrics qua endpoint `/actuator/prometheus` (format text/plain)
2. **Prometheus** tự động scrape (pull) metrics từ các service mỗi 10 giây
3. **Grafana** query Prometheus bằng PromQL để hiển thị dashboard

> **Tại sao dùng Pull model?**
> Prometheus chủ động kéo metrics từ services, không cần service push data.
> Ưu điểm: service không cần biết Prometheus tồn tại, dễ scale, dễ debug.

---

## 2. Các Thành Phần

### 2.1 Micrometer (Spring Boot side)

**Micrometer** là thư viện instrumentation cho Java, tương tự SLF4J nhưng cho metrics thay vì logging.

```xml
<!-- pom.xml — Thêm vào mỗi service -->
<!-- Spring Boot Actuator (đã có sẵn) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- Micrometer Prometheus Registry — format metrics cho Prometheus -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <scope>runtime</scope>
</dependency>
```

**Micrometer tự động thu thập:**

| Loại Metric | Ví dụ | Mô tả |
|-------------|-------|-------|
| JVM Memory | `jvm_memory_used_bytes` | Bộ nhớ heap/non-heap đang dùng |
| JVM Threads | `jvm_threads_live_threads` | Số thread đang sống |
| JVM GC | `jvm_gc_pause_seconds` | Thời gian GC pause |
| HTTP Requests | `http_server_requests_seconds` | Request rate, latency, status |
| CPU | `process_cpu_usage` | CPU usage của process |
| Uptime | `process_uptime_seconds` | Thời gian đã chạy |
| Logback | `logback_events_total` | Số log events theo level |

### 2.2 Prometheus

**Prometheus** là hệ thống monitoring & alerting open-source, sử dụng time-series database.

- **Storage**: Lưu metrics theo time-series (metric name + labels + timestamp + value)
- **PromQL**: Ngôn ngữ query mạnh mẽ
- **Service Discovery**: Tự động tìm targets để scrape

### 2.3 Grafana

**Grafana** là nền tảng visualization, kết nối với nhiều datasource (Prometheus, Elasticsearch, etc.)

- **Dashboard**: Tạo bảng điều khiển tùy chỉnh
- **Alerting**: Cảnh báo khi metrics vượt ngưỡng
- **Provisioning**: Tự động cấu hình datasource và dashboard

---

## 3. Cấu Hình Chi Tiết

### 3.1 Spring Boot — `application.yml`

```yaml
# Expose endpoint prometheus cho Prometheus scrape
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics   # ← thêm prometheus, metrics
  endpoint:
    health:
      show-details: always     # Hiện chi tiết health check
  metrics:
    tags:
      application: order-service   # ← Tag phân biệt service trong Prometheus
```

**Giải thích:**
- `prometheus` endpoint: xuất metrics ở format Prometheus text
- `metrics` endpoint: REST API xem metrics (debug)
- `tags.application`: gắn label `application=order-service` vào mọi metric

### 3.2 Prometheus — `docker/prometheus/prometheus.yml`

```yaml
global:
  scrape_interval: 15s       # Mặc định scrape mỗi 15 giây
  evaluation_interval: 15s   # Đánh giá alert rules mỗi 15 giây

scrape_configs:
  # Prometheus tự monitor chính nó
  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']

  # User Service
  - job_name: 'user-service'
    metrics_path: '/actuator/prometheus'    # ← Endpoint Spring Boot expose
    scrape_interval: 10s                    # ← Override: scrape mỗi 10s
    static_configs:
      - targets: ['host.docker.internal:8081']   # ← host.docker.internal = host machine
        labels:
          service: 'user-service'          # ← Custom label

  # Order Service
  - job_name: 'order-service'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 10s
    static_configs:
      - targets: ['host.docker.internal:8082']
        labels:
          service: 'order-service'

  # API Gateway
  - job_name: 'api-gateway'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 10s
    static_configs:
      - targets: ['host.docker.internal:8080']
        labels:
          service: 'api-gateway'
```

**Giải thích:**
- `host.docker.internal`: DNS đặc biệt trong Docker, trỏ về host machine
- `metrics_path`: Mặc định Prometheus scrape `/metrics`, Spring Boot dùng `/actuator/prometheus`
- `job_name`: Tên job, sẽ thành label `job="user-service"` trong metrics
- `labels`: Custom labels gắn thêm vào metrics

### 3.3 Docker Compose — Prometheus & Grafana

```yaml
# ==================== Prometheus ====================
prometheus:
  image: prom/prometheus:v3.4.1
  container_name: microservice-prometheus
  restart: unless-stopped
  extra_hosts:
    - "host.docker.internal:host-gateway"    # ← Cho phép truy cập host machine
  volumes:
    - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml   # ← Config file
    - prometheus_data:/prometheus                                  # ← Persistent data
  ports:
    - "9093:9090"              # ← Host port 9093 (tránh xung đột gRPC 9091)
  networks:
    - microservice-network

# ==================== Grafana ====================
grafana:
  image: grafana/grafana:12.1.0
  container_name: microservice-grafana
  restart: unless-stopped
  environment:
    GF_SECURITY_ADMIN_USER: admin          # ← Tài khoản admin
    GF_SECURITY_ADMIN_PASSWORD: admin      # ← Mật khẩu admin
    GF_USERS_ALLOW_SIGN_UP: "false"        # ← Tắt đăng ký
  volumes:
    - grafana_data:/var/lib/grafana                          # ← Persistent data
    - ./grafana/provisioning:/etc/grafana/provisioning       # ← Auto-config
    - ./grafana/dashboards:/var/lib/grafana/dashboards       # ← Pre-built dashboards
  ports:
    - "3000:3000"
  depends_on:
    - prometheus
  networks:
    - microservice-network
```

### 3.4 Grafana Provisioning — Auto-Config

**Datasource** (`docker/grafana/provisioning/datasources/prometheus.yml`):

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090      # ← Tên container (Docker internal DNS)
    uid: prometheus                  # ← UID cố định, dashboard reference UID này
    isDefault: true
    editable: true
```

**Dashboard Provider** (`docker/grafana/provisioning/dashboards/dashboard.yml`):

```yaml
apiVersion: 1
providers:
  - name: 'Microservice Dashboards'
    orgId: 1
    folder: ''
    type: file
    disableDeletion: false
    editable: true
    options:
      path: /var/lib/grafana/dashboards     # ← Thư mục chứa dashboard JSON
```

> **Provisioning là gì?**
> Cho phép Grafana tự động cấu hình datasource và import dashboard khi khởi động.
> Không cần config thủ công qua UI.

---

## 4. Khởi Động & Truy Cập

### Bước 1: Start infrastructure

```powershell
cd docker
docker compose up -d prometheus grafana
```

### Bước 2: Compile & start services

```powershell
cd c:\Users\phankien\Documents\JAVA
.\mvnw.cmd clean compile
.\mvnw.cmd -pl discovery-server spring-boot:run
.\mvnw.cmd -pl api-gateway spring-boot:run
.\mvnw.cmd -pl user-service spring-boot:run
.\mvnw.cmd -pl order-service spring-boot:run
```

### Bước 3: Truy cập

| Service | URL | Credentials |
|---------|-----|-------------|
| **Prometheus UI** | http://localhost:9093 | — |
| **Prometheus Targets** | http://localhost:9093/targets | — |
| **Grafana** | http://localhost:3000 | admin / admin |
| User metrics raw | http://localhost:8081/actuator/prometheus | — |
| Order metrics raw | http://localhost:8082/actuator/prometheus | — |
| Gateway metrics raw | http://localhost:8080/actuator/prometheus | — |

### Bước 4: Kiểm tra

1. Truy cập **Prometheus Targets** → tất cả services phải **UP** (xanh)
2. Truy cập **Grafana** → Dashboards → **Microservice Monitoring**
3. Gửi vài HTTP request → thấy metrics cập nhật real-time

---

## 5. Giải Thích Metrics

### 5.1 HTTP Request Metrics

```
# Số request đã xử lý
http_server_requests_seconds_count{
  method="GET",
  uri="/api/users/{id}",
  status="200",
  job="user-service"
}

# Tổng thời gian xử lý (giây)
http_server_requests_seconds_sum{...}

# Histogram buckets (phân bố latency)
http_server_requests_seconds_bucket{le="0.1", ...}
```

**PromQL hữu ích:**

```promql
# Request rate (req/s) trong 1 phút
rate(http_server_requests_seconds_count[1m])

# Average response time
rate(http_server_requests_seconds_sum[1m]) / rate(http_server_requests_seconds_count[1m])

# P95 response time
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))

# Error rate (5xx)
rate(http_server_requests_seconds_count{status=~"5.."}[1m])
```

### 5.2 JVM Metrics

```promql
# Heap memory sử dụng
jvm_memory_used_bytes{area="heap"}

# Max heap
jvm_memory_max_bytes{area="heap"}

# Live threads
jvm_threads_live_threads

# GC pause time
rate(jvm_gc_pause_seconds_sum[1m])

# CPU usage (0-1)
process_cpu_usage
system_cpu_usage

# Uptime
process_uptime_seconds
```

### 5.3 Metric Types

| Type | Mô tả | Ví dụ |
|------|-------|-------|
| **Counter** | Chỉ tăng, không giảm | `http_server_requests_seconds_count` |
| **Gauge** | Tăng/giảm tự do | `jvm_memory_used_bytes` |
| **Histogram** | Phân bố giá trị (buckets) | `http_server_requests_seconds_bucket` |
| **Summary** | Tương tự Histogram, tính quantile | `jvm_gc_pause_seconds` |

---

## 6. Grafana Dashboard

### Dashboard có sẵn: **Microservice Monitoring**

Dashboard được pre-provisioned với các panel:

### 📊 Overview Row
| Panel | Metric | Mô tả |
|-------|--------|-------|
| Uptime | `process_uptime_seconds` | Thời gian service đã chạy |
| CPU Usage | `process_cpu_usage` | % CPU đang dùng |
| Live Threads | `jvm_threads_live_threads` | Số thread hoạt động |
| Heap Memory | `jvm_memory_used_bytes{area="heap"}` | Bộ nhớ heap đang dùng |

### 🌐 HTTP Requests Row
| Panel | Metric | Mô tả |
|-------|--------|-------|
| Request Rate | `rate(http_server_requests_seconds_count[1m])` | Số request/giây |
| Response Time (avg) | `sum/count` | Thời gian phản hồi trung bình |
| Error Rate (5xx) | `rate(...{status=~"5.."}[1m])` | Tỉ lệ lỗi server |
| Response Time P95 | `histogram_quantile(0.95, ...)` | 95% request nhanh hơn giá trị này |

### ☕ JVM Metrics Row
| Panel | Metric | Mô tả |
|-------|--------|-------|
| JVM Heap Memory | Used vs Max | Theo dõi memory leak |
| JVM Threads | Live, Daemon, Peak | Thread pool health |
| GC Pause Time | `rate(jvm_gc_pause_seconds_sum[1m])` | Impact của GC |
| CPU Usage Over Time | Process vs System CPU | So sánh CPU app vs hệ thống |

### Service Filter

Dashboard có **dropdown filter `service`** để chọn xem metrics của từng service hoặc tất cả.

---

## 7. Xử Lý Sự Cố

### Prometheus target DOWN

**Triệu chứng:** Target hiện trạng thái "DOWN" trong http://localhost:9093/targets

**Nguyên nhân & Fix:**

| Lỗi | Nguyên nhân | Fix |
|-----|------------|-----|
| `connection refused` | Service chưa chạy | Start service |
| `HTTP 404` | Thiếu micrometer dependency hoặc chưa expose endpoint | Kiểm tra pom.xml + application.yml |
| `context deadline exceeded` | Firewall/network block | Kiểm tra `host.docker.internal` resolve |

### Grafana "No data"

1. Kiểm tra Prometheus targets đều **UP**
2. Kiểm tra datasource: Grafana → Connections → Data sources → Prometheus → **Test**
3. Kiểm tra service filter dropdown (chọn "All" hoặc service cụ thể)

### Port conflict

| Port | Service | Giải pháp nếu xung đột |
|------|---------|------------------------|
| 3000 | Grafana | Đổi port trong docker-compose.yml |
| 9090 | Prometheus (internal) | Không đổi (container internal) |
| 9093 | Prometheus (host) | Đổi nếu cần |
| 9091 | gRPC (user-service) | **KHÔNG đổi** — Prometheus đã tránh |

### Reset Grafana (xóa data cũ)

```powershell
cd docker
docker compose stop grafana
docker compose rm -f grafana
docker volume rm docker_grafana_data
docker compose up -d grafana
```

---

## Cấu Trúc File

```
docker/
├── prometheus/
│   └── prometheus.yml                    # Prometheus scrape config
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/
│   │   │   └── prometheus.yml            # Auto-config Prometheus datasource
│   │   └── dashboards/
│   │       └── dashboard.yml             # Dashboard provider config
│   └── dashboards/
│       └── microservice-dashboard.json   # Pre-built dashboard
└── docker-compose.yml                    # Prometheus + Grafana services

order-service/
├── pom.xml                               # + micrometer-registry-prometheus
└── src/main/resources/
    └── application.yml                   # + prometheus endpoint exposed

user-service/
├── pom.xml                               # + micrometer-registry-prometheus
└── src/main/resources/
    └── application.yml                   # + prometheus endpoint exposed

api-gateway/
├── pom.xml                               # + micrometer-registry-prometheus
└── src/main/resources/
    └── application.yml                   # + prometheus endpoint exposed
```

---

## So Sánh: Prometheus vs ELK (đã tích hợp)

| Tiêu chí | Prometheus + Grafana | ELK Stack (đã có) |
|----------|---------------------|-------------------|
| **Mục đích** | Metrics & Monitoring | Logs & Search |
| **Dữ liệu** | Số liệu (CPU, memory, request count) | Text logs (ERROR, WARN, INFO) |
| **Câu hỏi trả lời** | "Hệ thống có khỏe không?" | "Lỗi gì xảy ra?" |
| **Storage** | Time-series DB | Inverted index (Elasticsearch) |
| **Query** | PromQL | KQL / Lucene |
| **Khi nào dùng** | Dashboard real-time, alerting | Debug, audit, search logs |

> **Kết hợp cả 2:**
> - Grafana dashboard thấy CPU spike → biết hệ thống có vấn đề
> - Kibana search logs → tìm nguyên nhân root cause
