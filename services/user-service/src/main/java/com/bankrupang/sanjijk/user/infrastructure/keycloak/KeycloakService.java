package com.bankrupang.sanjijk.user.infrastructure.keycloak;

import com.bankrupang.sanjijk.user.domain.UserRole;
import com.bankrupang.sanjijk.user.domain.exception.KeycloakLoginFailedException;
import com.bankrupang.sanjijk.user.domain.exception.KeycloakUnavailableException;
import com.bankrupang.sanjijk.user.domain.exception.UserKeycloakCreationFailedException;
import com.bankrupang.sanjijk.user.presentation.dto.response.KeycloakTokenResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakService {

    private final Keycloak keycloak;

    @Value("${keycloak.server-url}")
    private String serverUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    public UUID createUser(String username, String email, String password, UserRole role) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(email);
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setFirstName(username);

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        user.setCredentials(Collections.singletonList(credential));
        user.setRequiredActions(Collections.emptyList());

        UsersResource usersResource = keycloak.realm(realm).users();
        user.setAttributes(Map.of("role", List.of(role.name())));
        Response response = usersResource.create(user);

        if (response.getStatus() == 201) {
            String path = response.getLocation().getPath();
            String keycloakUserId = path.substring(path.lastIndexOf("/") + 1);
            return UUID.fromString(keycloakUserId);
        } else {
            log.error("Keycloak 유저 생성 실패. status={}, username={}", response.getStatus(), username);
            throw new UserKeycloakCreationFailedException();
        }
    }

    // 로그인 — Circuit Breaker 적용
    // Keycloak 응답 3초 초과 또는 실패율 50% 초과 시 loginFallback 호출
    @CircuitBreaker(name = "keycloak", fallbackMethod = "loginFallback")
    public KeycloakTokenResponse login(String username, String password) {
        try {
            Keycloak keycloakLogin = KeycloakBuilder.builder()
                    .serverUrl(serverUrl)
                    .realm(realm)
                    .grantType(OAuth2Constants.PASSWORD)
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .username(username)
                    .password(password)
                    .build();

            var tokenResponse = keycloakLogin.tokenManager().getAccessToken();
            return new KeycloakTokenResponse(
                    tokenResponse.getToken(),
                    tokenResponse.getRefreshToken()
            );
        } catch (NotAuthorizedException e) {
            throw new KeycloakLoginFailedException();
        } catch (jakarta.ws.rs.BadRequestException e) {
            log.error("Keycloak 로그인 400: {}", e.getResponse().readEntity(String.class));
            throw new KeycloakLoginFailedException();
        }
    }

    // login() fallback — Circuit OPEN 또는 타임아웃 시 호출
    private KeycloakTokenResponse loginFallback(String username, String password, Exception e) {
        log.error("[Circuit Breaker] Keycloak 로그인 불가 - username: {}, error: {}", username, e.getMessage());
        if (e instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            throw new KeycloakUnavailableException();
        }
        throw new KeycloakLoginFailedException();
    }

    public void deleteUser(UUID keycloakUserId) {
        keycloak.realm(realm).users().get(keycloakUserId.toString()).remove();
    }

    // 세션 폐기 — Circuit Breaker 적용
    // Keycloak 장애 시 세션 폐기 실패해도 로그인 자체는 계속 진행 (가용성 우선)
    @CircuitBreaker(name = "keycloak", fallbackMethod = "revokeSessionsFallback")
    public void revokeUserSessions(UUID keycloakUserId) {
        try {
            keycloak.realm(realm).users().get(keycloakUserId.toString()).logout();
            log.info("[Keycloak] 기존 세션 폐기 완료 - userId: {}", keycloakUserId);
        } catch (Exception e) {
            log.debug("[Keycloak] 세션 폐기 시도 - userId: {}, msg: {}", keycloakUserId, e.getMessage());
        }
    }

    // revokeUserSessions() fallback — 세션 폐기 실패는 로그인을 막지 않음
    private void revokeSessionsFallback(UUID keycloakUserId, Exception e) {
        log.warn("[Circuit Breaker] 세션 폐기 불가 - userId: {}, 로그인 계속 진행. error: {}",
                keycloakUserId, e.getMessage());
    }

    // Refresh Token으로 새 Access Token 발급 — Circuit Breaker 적용
    @CircuitBreaker(name = "keycloak", fallbackMethod = "refreshTokenFallback")
    public KeycloakTokenResponse refreshToken(String refreshToken) {
        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("grant_type", "refresh_token");
            params.add("client_id", clientId);
            params.add("client_secret", clientSecret);
            params.add("refresh_token", refreshToken);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String tokenUrl = serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";
            var response = new RestTemplate().postForEntity(
                    tokenUrl,
                    new HttpEntity<>(params, headers),
                    java.util.Map.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new KeycloakLoginFailedException();
            }

            String newAccessToken = (String) response.getBody().get("access_token");
            String newRefreshToken = (String) response.getBody().get("refresh_token");
            return new KeycloakTokenResponse(newAccessToken, newRefreshToken);
        } catch (Exception e) {
            log.warn("[Keycloak] 토큰 갱신 실패: {}", e.getMessage());
            throw new KeycloakLoginFailedException();
        }
    }

    // refreshToken() fallback
    private KeycloakTokenResponse refreshTokenFallback(String refreshToken, Exception e) {
        log.error("[Circuit Breaker] Keycloak 토큰 갱신 불가 - error: {}", e.getMessage());
        if (e instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            throw new KeycloakUnavailableException();
        }
        throw new KeycloakLoginFailedException();
    }
}
