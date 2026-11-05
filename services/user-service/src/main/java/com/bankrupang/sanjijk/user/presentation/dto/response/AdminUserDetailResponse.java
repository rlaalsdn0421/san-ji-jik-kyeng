package com.bankrupang.sanjijk.user.presentation.dto.response;

import com.bankrupang.sanjijk.user.domain.UserRole;
import com.bankrupang.sanjijk.user.domain.UserStatus;
import com.bankrupang.sanjijk.user.domain.entity.User;

import java.util.UUID;

public record AdminUserDetailResponse(
        UUID userId,
        String name,
        String email,
        String phone,
        String businessNumber,
        String slackId,
        UserRole role,
        UserStatus status
) {
    public static AdminUserDetailResponse from(User user) {
        return new AdminUserDetailResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getBusinessNumber(),
                user.getSlackId(),
                user.getRole(),
                user.getStatus()
        );
    }
}
