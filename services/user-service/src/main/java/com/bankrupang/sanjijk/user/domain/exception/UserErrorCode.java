package com.bankrupang.sanjijk.user.domain.exception;

import com.bankrupang.sanjijk.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER-001", "이미 존재하는 username입니다"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER-002", "이미 존재하는 이메일입니다"),
    BUSINESS_NUMBER_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER-003", "이미 등록된 사업자번호입니다"),
    KEYCLOAK_USER_CREATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "USER-004", "keycloak 계정 생성에 실패했습니다"),
    INVALID_ADMIN_KEY(HttpStatus.UNAUTHORIZED, "USER-005", "관리자 키가 올바르지 않습니다"),
    INVALID_ROLE_FOR_SIGNUP(HttpStatus.BAD_REQUEST, "USER-006", "해당 엔드포인트에서 사용할 수 없는 역할입니다"),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "USER-007", "로그인에 실패하였습니다"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER-008", "해당 유저를 찾을 수 없습니다"),
    USER_SUSPENDED(HttpStatus.FORBIDDEN, "USER-009", "해당 계정은 일시 정지 상태입니다"),
    USER_DELETED(HttpStatus.FORBIDDEN,"USER-010", "해당 계정은 탈퇴된 계정입니다"),
    USER_NOT_SUSPENDED(HttpStatus.CONFLICT, "USER-011", "정지 상태가 아닌 계정입니다"),
    KEYCLOAK_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "USER-012", "인증 서버에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해주세요");


    private final HttpStatus status;
    private final String code;
    private final String message;
}
