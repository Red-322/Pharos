package com.pharos.rag.common.security;

import com.pharos.rag.common.enums.SystemRole;

/**
 * JWT 解析成功后放入 {@link UserContext} 的用户信息。
 */
public record AuthenticatedUser(
        Long userId,
        String userCode,
        String displayName,
        SystemRole systemRole,
        boolean mustChangePassword
) {
}
