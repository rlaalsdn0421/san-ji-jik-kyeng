package com.bankrupang.sanjijk.user.presentation.controller;

import com.bankrupang.sanjijk.common.response.ApiResponse;
import com.bankrupang.sanjijk.common.response.PageResponse;
import com.bankrupang.sanjijk.user.application.service.UserService;
import com.bankrupang.sanjijk.user.domain.UserRole;
import com.bankrupang.sanjijk.user.domain.UserStatus;
import com.bankrupang.sanjijk.user.presentation.dto.request.*;
import com.bankrupang.sanjijk.user.presentation.dto.response.AdminUserDetailResponse;
import com.bankrupang.sanjijk.user.presentation.dto.response.UserListResponse;
import com.bankrupang.sanjijk.user.presentation.dto.response.UserLoginResponse;
import com.bankrupang.sanjijk.user.presentation.dto.response.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/api/v1/auth/signup")
    public ResponseEntity<ApiResponse<UserResponse>> signup(
            @RequestBody @Valid UserSignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.create(userService.signup(request)));
    }

    @PostMapping("/api/v1/auth/admin/signup")
    public ResponseEntity<ApiResponse<UserResponse>> adminSignup(
            @RequestBody @Valid UserAdminSignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.create(userService.adminSignup(request)));
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<ApiResponse<UserLoginResponse>> login(
            @RequestBody @Valid UserLoginRequest request) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.ok(userService.login(request)));
    }

    @PreAuthorize("hasAnyRole('MASTER','MANAGER')")
    @GetMapping("/api/v1/users/one")
    public ResponseEntity<ApiResponse<AdminUserDetailResponse>> getUser(
            @RequestParam UUID userId) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.ok(userService.getAdminUserDetail(userId)));
    }

    @PreAuthorize("hasAnyRole('MASTER','MANAGER')")
    @GetMapping("/api/v1/users/all")
    public ResponseEntity<ApiResponse<PageResponse<UserListResponse>>> getUsers(
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<UserListResponse> response = userService.getUsers(role, status, page, size);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // 내 프로필 조회
    @GetMapping("/api/v1/users/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMe(
            @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.ok(userService.getUser(userId)));
    }

    // 유저 프로필 수정
    @PatchMapping("/api/v1/users/me/profile")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @RequestHeader("X-User-Id")UUID userId,
            @Valid @RequestBody UserInfoUpdateRequest request) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.ok(userService.updateUserInfo(userId, request)));
    }

    // 사업자 번호 변경
    @PatchMapping("/api/v1/users/me/business")
    public ResponseEntity<ApiResponse<UserResponse>> updateBusinessNumber(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody UserBusinessNumberUpdateRequest request) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.ok(userService.updateBusinessNumber(userId, request)));
    }

    // 탈퇴
    @DeleteMapping("/api/v1/users/me")
    public ResponseEntity<ApiResponse<Void>> deleteUser(
        @RequestHeader("X-User-Id") UUID userId) {
        userService.deleteUser(userId);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    // 유저 계정 정지 [마스터]
    @PreAuthorize("hasRole('MASTER')")
    @PatchMapping("/api/v1/users/suspended")
    public ResponseEntity<ApiResponse<Void>> suspendUser(
            @RequestBody UserSuspendedRequest request) {
        userService.suspendUser(request);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    // 유저 계정 정지 혜지 [마스터]
    @PreAuthorize("hasRole('MASTER')")
    @PatchMapping("/api/v1/users/unsuspended")
    public ResponseEntity<ApiResponse<Void>> unsuspendUser(
            @RequestBody UserSuspendedRequest request) {
        userService.unsuspendUser(request);
        return ResponseEntity.ok(ApiResponse.ok());
    }
    // Refresh Token으로 Access Token 재발급
    @PostMapping("/api/v1/auth/refresh")
    public ResponseEntity<ApiResponse<UserLoginResponse>> refresh(
            @RequestBody @Valid RefreshTokenRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.refreshToken(request.refreshToken())));
    }

}