package com.application.dto;

import java.math.BigDecimal;

public record OrderItemDto(
    Long productId,
    String productName,
    String productCode,
    BigDecimal unitPrice,
    Integer quantity,
    BigDecimal subtotal
) {}
