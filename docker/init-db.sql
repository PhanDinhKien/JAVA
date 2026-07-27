-- Create database for user-service
-- (POSTGRES_DB from .env is created automatically by the PostgreSQL container)

-- Create additional databases for future services if needed
-- CREATE DATABASE order_service_db;
-- CREATE DATABASE product_service_db;

-- ═══════════════════════════════════════════════════
-- Order Statistics table (user-service)
-- Cập nhật tự động qua RabbitMQ event từ order-service
-- ═══════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS order_statistics (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL UNIQUE,
    total_orders    INTEGER NOT NULL DEFAULT 0,
    total_spending  NUMERIC(15,2) NOT NULL DEFAULT 0,
    last_order_at   TIMESTAMP,
    last_product_name VARCHAR(255),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_order_statistics_user_id ON order_statistics(user_id);
