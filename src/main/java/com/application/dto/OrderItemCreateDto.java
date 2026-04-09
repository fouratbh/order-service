package com.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record OrderItemCreateDto(
    @NotNull Long productId,
    @NotNull @Min(1) Integer quantity
) {}