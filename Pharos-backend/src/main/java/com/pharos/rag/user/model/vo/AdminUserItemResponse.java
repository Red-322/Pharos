package com.pharos.rag.user.model.vo;

import com.pharos.rag.common.enums.SystemRole;
import com.pharos.rag.common.enums.UserStatus;

import java.time.LocalDateTime;

/**
 * 管理员用户列表中的单条用户信息。
 */
public record AdminUserItemResponse(
        Long userId,
        String userCode,
        String username,
        String email,
        String displayName,
        SystemRole systemRole,
        UserStatus status,
        boolean mustChangePassword,
        LocalDateTime lastLoginAt
) {
}
