package com.microservice.userservice.service;

import com.microservice.userservice.dto.UserDto;
import com.microservice.userservice.dto.UserRoleDto;
import com.microservice.userservice.model.User;
import com.microservice.userservice.repository.UserRepository;
import com.microservice.userservice.repository.specification.UserSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public List<UserDto> getAllUsers(String name, String roleName) {
        Specification<User> spec = Specification
                .where(UserSpecification.isNotDeleted())
                .and(UserSpecification.hasName(name))
                .and(UserSpecification.hasRole(roleName));

        return userRepository.findAll(spec)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public UserDto getUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));
        return toDto(user);
    }

    @Transactional
    public UserDto updateUser(UUID id, UserDto userDto) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));

        user.setName(userDto.getName());
        user.setEmail(userDto.getEmail());
        user.setPhoneNumber(userDto.getPhoneNumber());
        user.setAddress(userDto.getAddress());
        user.setAvatar(userDto.getAvatar());

        User updatedUser = userRepository.save(user);
        return toDto(updatedUser);
    }

    @Transactional
    public void deleteUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));
        // Soft delete
        user.setDeletedAt(java.time.LocalDateTime.now());
        user.setStatus("inactive");
        userRepository.save(user);
    }

    private UserDto toDto(User user) {
        List<UserRoleDto> roles = user.getRoles().stream()
                .map(role -> UserRoleDto.builder()
                        .id(role.getId())
                        .roleName(role.getRoleName())
                        .displayName(role.getDisplayName())
                        .description(role.getDescription())
                        .isDefault(role.getIsDefault())
                        .isSystem(role.getIsSystem())
                        .priority(role.getPriority())
                        .build())
                .toList();

        return UserDto.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .address(user.getAddress())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .roles(roles)
                .build();
    }
}

