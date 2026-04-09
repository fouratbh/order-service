package com.application.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.application.enums.OrderStatus;

public record OrderDto(
    Long id,
    String orderNumber,
    String customerUsername,
    OrderStatus status,
    BigDecimal totalAmount,
    String notes,
    LocalDateTime createdAt,
    LocalDateTime confirmedAt,
    List<OrderItemDto> items
) {}