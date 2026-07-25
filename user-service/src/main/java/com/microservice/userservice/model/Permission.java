package com.microservice.userservice.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "permission_key", nullable = false, unique = true, length = 250)
    private String permissionKey;

    @Column(columnDefinition = "jsonb")
    private String name;

    @Column(columnDefinition = "jsonb")
    private String description;

    @Column(name = "module_group", length = 100)
    private String moduleGroup;

    @Column(name = "service_code", length = 100)
    private String serviceCode;

    @Column(length = 100)
    private String action;

    @Column(length = 50)
    private String scope;

    @Column(name = "parent_permission_id")
    private UUID parentPermissionId;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
