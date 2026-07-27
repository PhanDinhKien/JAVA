package com.microservice.orderservice.exception;

/**
 * Exception khi một service phụ thuộc không khả dụng (chết, timeout, ...)
 *
 * PHÂN BIỆT với các lỗi nghiệp vụ:
 * - ServiceUnavailableException: service chết → có thể retry/fallback
 * - RuntimeException("User not found"): user thật sự không tồn tại → lỗi nghiệp vụ
 */
public class ServiceUnavailableException extends RuntimeException {

    private final String serviceName;

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
