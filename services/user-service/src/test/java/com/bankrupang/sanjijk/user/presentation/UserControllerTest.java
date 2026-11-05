package com.bankrupang.sanjijk.user.presentation;

import com.bankrupang.sanjijk.common.exception.GlobalExceptionHandler;
import com.bankrupang.sanjijk.user.application.service.UserService;
import com.bankrupang.sanjijk.user.domain.UserRole;
import com.bankrupang.sanjijk.user.domain.UserStatus;
import com.bankrupang.sanjijk.user.domain.exception.UserNotFoundException;
import com.bankrupang.sanjijk.user.domain.exception.UserSuspendedException;
import com.bankrupang.sanjijk.common.config.CommonSecurityConfig;
import com.bankrupang.sanjijk.user.presentation.controller.UserController;
import com.bankrupang.sanjijk.user.presentation.dto.request.*;
import com.bankrupang.sanjijk.user.presentation.dto.response.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import({CommonSecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class UserControllerTest {


    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean UserService userService;

    private static final UUID USER_ID = UUID.randomUUID();

    // ──────────────────────────────────────────────────
    // 회원가입
    // ──────────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/v1/auth/signup")
    class SignupApi {

        @Test
        @DisplayName("성공 - 201 Created")
        void signup_success() throws Exception {
            var request = new UserSignupRequest(
                    "testuser", "테스트유저", "test@test.com",
                    "010-1234-5678", "Password1!", "123-45-67890",
                    "slack-id", true, UserRole.BUYER
            );
            var response = new UserResponse(USER_ID, "testuser", "테스트유저",
                    "test@test.com", "010-1234-5678", null, null, true, UserRole.BUYER, UserStatus.ACTIVE);
            given(userService.signup(any())).willReturn(response);

            mockMvc.perform(post("/api/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.username").value("testuser"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("username 유효성 검사 실패 - 400 Bad Request")
        void signup_invalidUsername_400() throws Exception {
            var request = new UserSignupRequest(
                    "ab",  // 4자 미만
                    "테스트유저", "test@test.com",
                    "010-1234-5678", "Password1!", "123-45-67890",
                    "slack-id", true, UserRole.BUYER
            );

            mockMvc.perform(post("/api/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("이메일 형식 오류 - 400 Bad Request")
        void signup_invalidEmail_400() throws Exception {
            var request = new UserSignupRequest(
                    "testuser", "테스트유저", "not-an-email",
                    "010-1234-5678", "Password1!", "123-45-67890",
                    "slack-id", true, UserRole.BUYER
            );

            mockMvc.perform(post("/api/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ──────────────────────────────────────────────────
    // 관리자 회원가입
    // ──────────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/v1/auth/admin/signup")
    class AdminSignupApi {

        @Test
        @DisplayName("성공 - 201 Created")
        void adminSignup_success() throws Exception {
            var request = new UserAdminSignupRequest(
                    "adminuser", "관리자", "admin@test.com",
                    "010-9999-8888", "Password1!", "slack-admin",
                    true, UserRole.MANAGER, "manager-secret"
            );
            var response = new UserResponse(USER_ID, "adminuser", "관리자",
                    "admin@test.com", "010-9999-8888", null, null, true, UserRole.MANAGER, UserStatus.ACTIVE);
            given(userService.adminSignup(any())).willReturn(response);

            mockMvc.perform(post("/api/v1/auth/admin/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.role").value("MANAGER"));
        }
    }

    // ──────────────────────────────────────────────────
    // 로그인
    // ──────────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class LoginApi {

        @Test
        @DisplayName("성공 - 200 OK + 토큰 반환")
        void login_success() throws Exception {
            given(userService.login(any()))
                    .willReturn(new UserLoginResponse("access-token", "refresh-token"));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"testuser","password":"Password1!"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").value("access-token"));
        }

        @Test
        @DisplayName("정지된 유저 로그인 → 403 Forbidden")
        void login_suspendedUser_forbidden() throws Exception {
            given(userService.login(any())).willThrow(new UserSuspendedException());

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"suspended","password":"pw"}
                                    """))
                    .andExpect(status().isForbidden());
        }
    }

    // ──────────────────────────────────────────────────
    // 유저 단건 조회 (MASTER/MANAGER 전용)
    // ──────────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/v1/users/one")
    class GetUserApi {

        @Test
        @DisplayName("MASTER 권한으로 조회 성공 - 200 OK")
        void getUser_asMaster_success() throws Exception {
            var response = new AdminUserDetailResponse(USER_ID, "testuser", "테스트유저",
                    "test@test.com", "010-1234-5678", "U122DSDFW",UserRole.BUYER, UserStatus.ACTIVE);
            given(userService.getAdminUserDetail(any())).willReturn(response);

            mockMvc.perform(get("/api/v1/users/one")
                            .param("userId", USER_ID.toString())
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "MASTER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("testuser"));
        }

        @Test
        @DisplayName("인증 없이 접근 → 403 Forbidden")
        void getUser_noAuth_403() throws Exception {
            mockMvc.perform(get("/api/v1/users/one")
                            .param("userId", USER_ID.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BUYER 권한으로 접근 → 403 Forbidden")
        void getUser_buyerRole_403() throws Exception {
            mockMvc.perform(get("/api/v1/users/one")
                            .param("userId", USER_ID.toString())
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "BUYER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("존재하지 않는 유저 → 404 Not Found")
        void getUser_notFound_404() throws Exception {
            given(userService.getAdminUserDetail(any())).willThrow(new UserNotFoundException());

            mockMvc.perform(get("/api/v1/users/one")
                            .param("userId", USER_ID.toString())
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "MASTER"))
                    .andExpect(status().isNotFound());
        }
    }

    // ──────────────────────────────────────────────────
    // 프로필 수정
    // ──────────────────────────────────────────────────
    @Nested
    @DisplayName("PATCH /api/v1/users/me/profile")
    class UpdateProfileApi {

        @Test
        @DisplayName("성공 - 200 OK")
        void updateProfile_success() throws Exception {
            var response = new UserResponse(USER_ID, "testuser", "새이름",
                    "test@test.com", "010-9999-9999", null, null, true, UserRole.BUYER, UserStatus.ACTIVE);
            given(userService.updateUserInfo(any(), any())).willReturn(response);

            mockMvc.perform(patch("/api/v1/users/me/profile")
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "BUYER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"새이름","phone":"010-9999-9999","slackId":"new-slack"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("새이름"));
        }

        @Test
        @DisplayName("인증 없이 접근 → 403 Forbidden")
        void updateProfile_noAuth_403() throws Exception {
            mockMvc.perform(patch("/api/v1/users/me/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"새이름"}
                                    """))
                    .andExpect(status().isForbidden());
        }
    }

    // ──────────────────────────────────────────────────
    // 탈퇴
    // ──────────────────────────────────────────────────
    @Nested
    @DisplayName("DELETE /api/v1/users/me")
    class DeleteUserApi {

        @Test
        @DisplayName("성공 - 200 OK")
        void deleteUser_success() throws Exception {
            willDoNothing().given(userService).deleteUser(any());

            mockMvc.perform(delete("/api/v1/users/me")
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "BUYER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ──────────────────────────────────────────────────
    // 계정 정지 / 해제 (MASTER 전용)
    // ──────────────────────────────────────────────────
    @Nested
    @DisplayName("PATCH /api/v1/users/suspended|unsuspended")
    class SuspensionApi {

        @Test
        @DisplayName("정지 성공 - MASTER 권한 - 200 OK")
        void suspendUser_asMaster_success() throws Exception {
            willDoNothing().given(userService).suspendUser(any());

            mockMvc.perform(patch("/api/v1/users/suspended")
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "MASTER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.format("{\"userId\":\"%s\"}", USER_ID)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("정지 - MANAGER 권한으로 접근 → 403 Forbidden")
        void suspendUser_asManager_403() throws Exception {
            mockMvc.perform(patch("/api/v1/users/suspended")
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "MANAGER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.format("{\"userId\":\"%s\"}", USER_ID)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("정지 해제 성공 - MASTER 권한 - 200 OK")
        void unsuspendUser_asMaster_success() throws Exception {
            willDoNothing().given(userService).unsuspendUser(any());

            mockMvc.perform(patch("/api/v1/users/unsuspended")
                            .header("X-User-Id", USER_ID.toString())
                            .header("X-User-Role", "MASTER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.format("{\"userId\":\"%s\"}", USER_ID)))
                    .andExpect(status().isOk());
        }
    }
}
