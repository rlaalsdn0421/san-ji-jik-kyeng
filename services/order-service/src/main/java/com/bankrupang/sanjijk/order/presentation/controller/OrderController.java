package com.bankrupang.sanjijk.order.presentation.controller;

import com.bankrupang.sanjijk.common.response.ApiResponse;
import com.bankrupang.sanjijk.order.application.service.OrderService;
import com.bankrupang.sanjijk.order.presentation.dto.request.OrderDepositCreateRequest;
import com.bankrupang.sanjijk.order.presentation.dto.response.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.data.domain.Page;
import java.util.UUID;

@Tag(name = "Order", description = "주문 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "보증금 주문 생성", description = "경매 입장 전 보증금 결제 주문")
    @PostMapping("/deposit")
    public ResponseEntity<ApiResponse<OrderResponse>> createDepositOrder(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody OrderDepositCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.create(orderService.createDepositOrder(userId, request)));
    }

    @Operation(summary = "내 보증금 조회", description = "사용자 본인의 보증금을 조회합니다.")
    @GetMapping("/deposit/me")
    public ResponseEntity<ApiResponse<Page<OrderResponse>>> getMyDepositOrders(
            @RequestHeader("X-User-Id") UUID userId,
            @ParameterObject Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.getMyDepositOrders(userId, pageable)));
    }

    @Operation(summary = "내 낙찰금 조회", description = "사용자 본인의 낙찰금을 조회합니다.")
    @GetMapping("/winning/me")
    public ResponseEntity<ApiResponse<Page<OrderResponse>>> getMyWinningOrders(
            @RequestHeader("X-User-Id") UUID userId,
            @ParameterObject Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.getMyWinningOrders(userId, pageable)));
    }
}
