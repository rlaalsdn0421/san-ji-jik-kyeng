package com.bankrupang.sanjijk.user.domain.exception;

import com.bankrupang.sanjijk.common.exception.BaseException;

public class KeycloakUnavailableException extends BaseException {
    public KeycloakUnavailableException() {
        super(UserErrorCode.KEYCLOAK_UNAVAILABLE);
    }
}
