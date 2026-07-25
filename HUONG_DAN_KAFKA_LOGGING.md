# Hướng Dẫn Tích Hợp Kafka Buffer Cho ELK Logging

## Mục Lục
1. [Vấn đề cần giải quyết](#1-vấn-đề-cần-giải-quyết)
2. [Kiến trúc mới với Kafka](#2-kiến-trúc-mới-với-kafka)
3. [Bước 1: Thêm Kafka vào Docker Compose](#3-bước-1-thêm-kafka-vào-docker-compose)
4. [Bước 2: Thêm thư viện Kafka Appender](#4-bước-2-thêm-thư-viện-kafka-appender)
5. [Bước 3: Cấu hình logback-spring.xml](#5-bước-3-cấu-hình-logback-springxml)
6. [Bước 4: Cấu hình Logstash consume từ Kafka](#6-bước-4-cấu-hình-logstash-consume-từ-kafka)
7. [Khởi chạy và kiểm tra](#7-khởi-chạy-và-kiểm-tra)
8. [Test fault tolerance](#8-test-fault-tolerance)

---

## 1. Vấn Đề Cần Giải Quyết

### Trước (TCP trực tiếp):
```
Service ──TCP──→ Logstash ──→ Elasticsearch
                    │
    ES/Logstash chết → LOG MẤT HOÀN TOÀN
```

### Sau (có Kafka buffer):
```
Service ──→ Kafka ──→ Logstash ──→ Elasticsearch
               │
    Kafka giữ log trên disk 7 ngày
    ES chết → Kafka vẫn nhận log
    ES sống lại → Logstash replay từ Kafka
    → KHÔNG MẤT LOG
```

---

## 2. Kiến Trúc Mới Với Kafka

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│ user-service │     │order-service │     │ api-gateway  │     │              │
│              │     │              │     │              │     │              │
│  KafkaAppender ──→ │  KafkaAppender ──→ │  KafkaAppender ──→ │    KAFKA     │
│              │     │              │     │              │     │  topic:      │
└──────────────┘     └──────────────┘     └──────────────┘     │  microservice│
                                                               │  -logs       │
                                                               │              │
                                                               │ retention:   │
                                                               │ 7 ngày       │
                                                               └──────┬───────┘
                                                                      │
                                                               ┌──────▼───────┐
                                                               │  LOGSTASH    │
                                                               │  (consumer)  │
                                                               └──────┬───────┘
                                                                      │
                                                               ┌──────▼───────┐
                                                               │ELASTICSEARCH │
                                                               └──────┬───────┘
                                                                      │
                                                               ┌──────▼───────┐
                                                               │   KIBANA     │
                                                               └──────────────┘
```

---

## 3. Bước 1: Thêm Kafka Vào Docker Compose

```yaml
kafka:
  image: apache/kafka:3.9.0
  container_name: microservice-kafka
  restart: unless-stopped
  environment:
    # ═══ KRaft mode (không cần Zookeeper) ═══
    # KRaft = Kafka Raft — Kafka tự quản lý metadata
    # Không cần thêm container Zookeeper → đơn giản hơn
    KAFKA_NODE_ID: 1
    KAFKA_PROCESS_ROLES: broker,controller
    KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:29093
    KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER

    # ═══ Listeners ═══
    # PLAINTEXT (9092): Cho các container trong cùng Docker network (Logstash)
    # PLAINTEXT_HOST (29092): Cho ứng dụng chạy trên host (services)
    KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:29093,PLAINTEXT_HOST://0.0.0.0:29092
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:29092
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT

    # ═══ Topic config ═══
    KAFKA_LOG_RETENTION_HOURS: 168          # Giữ log 7 ngày (168 giờ)
    KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true" # Tự tạo topic khi producer gửi message

    # ═══ Cluster ID ═══
    CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk     # ID cố định cho KRaft cluster
  ports:
    - "29092:29092"           # Host applications kết nối qua port này
  volumes:
    - kafka_data:/var/kafka/data    # Persist data → restart không mất log
```

### Giải thích 2 port Kafka:

| Port | Listener | Ai dùng | VD |
|------|----------|---------|-----|
| `9092` | PLAINTEXT | Containers trong Docker network | Logstash → `kafka:9092` |
| `29092` | PLAINTEXT_HOST | Ứng dụng trên máy host | Service → `localhost:29092` |

---

## 4. Bước 2: Thêm Thư Viện Kafka Appender

Thêm vào `pom.xml` của **mỗi service**:

```xml
<!--
    logback-kafka-appender: Logback appender gửi log đến Kafka
    Thay thế LogstashTcpSocketAppender (gửi TCP trực tiếp)
    → Gửi qua Kafka (có buffer, persistence)
-->
<dependency>
    <groupId>com.github.danielwegener</groupId>
    <artifactId>logback-kafka-appender</artifactId>
    <version>0.2.0-RC2</version>
</dependency>

<!--
    kafka-clients: Apache Kafka Java client
    logback-kafka-appender cần thư viện này để kết nối Kafka
    Version được quản lý bởi Spring Boot parent POM
-->
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-clients</artifactId>
</dependency>
```

> **Lưu ý**: Giữ nguyên `logstash-logback-encoder` — vẫn cần để encode log thành JSON.

---

## 5. Bước 3: Cấu Hình logback-spring.xml

### Thay đổi chính: TCP Appender → Kafka Appender

```xml
<!-- ❌ CŨ: Gửi trực tiếp đến Logstash qua TCP -->
<appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
    <destination>localhost:5044</destination>
    ...
</appender>

<!-- ✅ MỚI: Gửi đến Kafka topic -->
<appender name="KAFKA" class="com.github.danielwegener.logback.kafka.KafkaAppender">
    <!--
        LogstashEncoder: Chuyển log thành JSON (giống cũ)
        JSON format giúp Logstash parse dễ dàng
    -->
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <customFields>{"service_name":"${appName}"}</customFields>
    </encoder>

    <!--
        topic: Kafka topic nhận log
        Tất cả service gửi vào CÙNG 1 topic
        → Logstash consume 1 topic = nhận hết log
    -->
    <topic>microservice-logs</topic>

    <!--
        keyingStrategy: Cách chọn partition key
        NoKeyKeyingStrategy = round-robin giữa các partition
        → Phân bổ đều tải
    -->
    <keyingStrategy class="com.github.danielwegener.logback.kafka.keying.NoKeyKeyingStrategy"/>

    <!--
        deliveryStrategy: Async = không block thread chính
        Log được đẩy vào buffer → gửi batch → nhanh hơn
    -->
    <deliveryStrategy class="com.github.danielwegener.logback.kafka.delivery.AsynchronousDeliveryStrategy"/>

    <!-- Kafka producer configs -->
    <producerConfig>bootstrap.servers=localhost:29092</producerConfig>
    <producerConfig>acks=0</producerConfig>          <!-- Fire-and-forget, nhanh nhất -->
    <producerConfig>linger.ms=100</producerConfig>   <!-- Batch 100ms trước khi gửi -->
    <producerConfig>buffer.memory=4194304</producerConfig>  <!-- 4MB buffer -->

    <!--
        Fallback appender: Nếu Kafka CŨNG chết
        → Log fallback ra Console (không mất hoàn toàn)
    -->
    <appender-ref ref="CONSOLE"/>
</appender>
```

---

## 6. Bước 4: Cấu Hình Logstash Consume Từ Kafka

```conf
input {
  # ❌ CŨ: Nhận TCP trực tiếp
  # tcp {
  #   port => 5044
  #   codec => json_lines
  # }

  # ✅ MỚI: Consume từ Kafka topic
  kafka {
    bootstrap_servers => "kafka:9092"          # Trong Docker network
    topics => ["microservice-logs"]            # Topic chứa log
    group_id => "logstash-consumer"            # Consumer group
    codec => json                              # Parse JSON
    auto_offset_reset => "earliest"            # Đọc từ đầu nếu restart
  }
}

output {
  elasticsearch {
    hosts => ["http://elasticsearch:9200"]
    index => "microservice-logs-%{+YYYY.MM.dd}"
  }
}
```

### Giải thích `auto_offset_reset => "earliest"`:

```
Kafka topic: [msg1] [msg2] [msg3] [msg4] [msg5]
                                          ↑
                                    Logstash đang đọc ở đây

Logstash restart → quên offset → bắt đầu từ đâu?
  - "latest": Bỏ qua msg cũ → có thể MẤT LOG
  - "earliest": Đọc lại từ msg1 → KHÔNG MẤT LOG (có thể duplicate nhưng an toàn hơn)
```

---

## 7. Khởi Chạy Và Kiểm Tra

### Bước 1: Restart Docker
```powershell
cd docker
docker compose down
docker compose up -d
```

### Bước 2: Kiểm tra Kafka đã sẵn sàng
```powershell
docker logs microservice-kafka --tail 20
# Tìm: "Kafka Server started"
```

### Bước 3: Restart services
```powershell
.\mvnw.cmd -pl discovery-server spring-boot:run
.\mvnw.cmd -pl user-service spring-boot:run
.\mvnw.cmd -pl order-service spring-boot:run
.\mvnw.cmd -pl api-gateway spring-boot:run
```

### Bước 4: Call API tạo log
```powershell
curl http://localhost:8081/api/users
```

### Bước 5: Kiểm tra Kafka topic có log
```powershell
docker exec microservice-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic microservice-logs \
  --from-beginning \
  --max-messages 5
```

### Bước 6: Kiểm tra Kibana
Truy cập http://localhost:5601 → Discover → thấy logs

---

## 8. Test Fault Tolerance

### Test: Elasticsearch chết → log không mất

```powershell
# 1. Stop Elasticsearch
docker stop microservice-elasticsearch

# 2. Gọi API (tạo log)
curl http://localhost:8081/api/users

# 3. Log vẫn nằm trong Kafka (kiểm tra)
docker exec microservice-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic microservice-logs \
  --from-beginning \
  --max-messages 5
# → Thấy log JSON ✅

# 4. Start lại Elasticsearch
docker start microservice-elasticsearch

# 5. Chờ 30s → Logstash tự consume từ Kafka → gửi sang ES
# 6. Kiểm tra Kibana → log xuất hiện ✅ → KHÔNG MẤT LOG
```

---

## Cấu Trúc File

```
project/
├── docker/
│   ├── docker-compose.yml         ← [SỬA] Thêm Kafka (KRaft)
│   └── logstash/
│       └── logstash.conf          ← [SỬA] Input: TCP → Kafka
│
├── user-service/
│   ├── pom.xml                    ← [SỬA] +logback-kafka-appender, +kafka-clients
│   └── src/main/resources/
│       └── logback-spring.xml     ← [SỬA] TCP Appender → Kafka Appender
│
├── order-service/
│   ├── pom.xml                    ← [SỬA] +logback-kafka-appender, +kafka-clients
│   └── src/main/resources/
│       └── logback-spring.xml     ← [SỬA] TCP Appender → Kafka Appender
│
└── api-gateway/
    ├── pom.xml                    ← [SỬA] +logback-kafka-appender, +kafka-clients
    └── src/main/resources/
        └── logback-spring.xml     ← [SỬA] TCP Appender → Kafka Appender
```
