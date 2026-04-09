package com.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record OrderCreateDto(
    String notes,
    @NotEmpty @Valid List<OrderItemCreateDto> items
) {}