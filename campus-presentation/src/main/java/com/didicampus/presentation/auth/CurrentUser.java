package com.didicampus.presentation.auth;

import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

public final class CurrentUser {

    public static final String ATTR = "currentUserId";

    private CurrentUser() {
    }

    public static long id() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object value = attributes == null ? null : attributes.getAttribute(ATTR, RequestAttributes.SCOPE_REQUEST);
        if (!(value instanceof Long userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
