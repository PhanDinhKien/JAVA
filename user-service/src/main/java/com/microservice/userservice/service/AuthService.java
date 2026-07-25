package com.microservice.userservice.service;

import com.microservice.userservice.dto.auth.*;
import com.microservice.userservice.model.BlacklistedToken;
import com.microservice.userservice.model.Role;
import com.microservice.userservice.model.User;
import com.microservice.userservice.repository.BlacklistedTokenRepository;
import com.microservice.userservice.repository.RoleRepository;
import com.microservice.userservice.repository.UserRepository;
import com.microservice.userservice.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BlacklistedTokenRepository blacklistedTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already exists");
        }
        if (userRepository.existsByName(request.getName())) {
            throw new RuntimeException("Name already exists");
        }

        User user = User.builder()
                .email(request.getEmail())
                .name(request.getName())
                .phoneNumber(request.getPhoneNumber())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .build();

        // Gán role mặc định (user)
        roleRepository.findByIsDefaultTrue()
                .ifPresent(role -> user.getRoles().add(role));

        User savedUser = userRepository.save(user);
        return buildAuthResponse(savedUser);
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmailAndDeletedAtIsNull(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Cập nhật last login
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        return buildAuthResponse(user);
    }

    @Transactional
    public void logout(String token) {
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        LocalDateTime expiresAt = jwtService.extractExpiration(token)
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        BlacklistedToken blacklistedToken = BlacklistedToken.builder()
                .token(token)
                .expiresAt(expiresAt)
                .build();

        blacklistedTokenRepository.save(blacklistedToken);
    }

    public AuthResponse refreshToken(TokenRefreshRequest request) {
        String refreshToken = request.getRefreshToken();

        // Kiểm tra blacklist
        if (blacklistedTokenRepository.existsByToken(refreshToken)) {
            throw new RuntimeException("Refresh token has been revoked");
        }

        // Kiểm tra loại token
        String tokenType = jwtService.extractTokenType(refreshToken);
        if (!"refresh".equals(tokenType)) {
            throw new RuntimeException("Invalid token type");
        }

        String email = jwtService.extractUsername(refreshToken);
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Kiểm tra token version
        Integer tokenVersion = jwtService.extractTokenVersion(refreshToken);
        if (!tokenVersion.equals(user.getTokenVersion())) {
            throw new RuntimeException("Token version mismatch. Please login again.");
        }

        // Blacklist refresh token cũ
        LocalDateTime expiresAt = jwtService.extractExpiration(refreshToken)
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        blacklistedTokenRepository.save(
                BlacklistedToken.builder()
                        .token(refreshToken)
                        .expiresAt(expiresAt)
                        .build()
        );

        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtService.generateAccessToken(user, user.getId(), user.getTokenVersion());
        String refreshToken = jwtService.generateRefreshToken(user, user.getId(), user.getTokenVersion());

        List<String> roles = user.getRoles().stream()
                .map(Role::getRoleName)
                .toList();

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .roles(roles)
                .build();
    }
}
