package com.microservice.userservice.dto;

import lombok.*;

import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRoleDto {
    private UUID id;
    private String roleName;
    private Map<String, String> displayName;
    private Map<String, String> description;
    private Boolean isDefault;
    private Boolean isSystem;
    private Integer priority;
}

