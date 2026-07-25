package com.microservice.userservice.controller;

import com.microservice.userservice.dto.UserDto;
import com.microservice.userservice.model.OrderStatistic;
import com.microservice.userservice.service.OrderStatisticService;
import com.microservice.userservice.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final OrderStatisticService orderStatisticService;

    @GetMapping
    public ResponseEntity<List<UserDto>> getAllUsers(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String roleName) {
        return ResponseEntity.ok(userService.getAllUsers(name, roleName));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUserById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserDto> updateUser(@PathVariable UUID id, @Valid @RequestBody UserDto userDto) {
        return ResponseEntity.ok(userService.updateUser(id, userDto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteUser(@PathVariable UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(Map.of("message", "User deleted successfully"));
    }

    /**
     * Lấy thống kê đơn hàng của user
     * Dữ liệu được cập nhật tự động qua RabbitMQ event
     */
    @GetMapping("/{id}/statistics")
    public ResponseEntity<OrderStatistic> getUserOrderStatistics(@PathVariable UUID id) {
        return ResponseEntity.ok(orderStatisticService.getStatisticByUserId(id));
    }
}
