package com.application.dto;

import java.math.BigDecimal;

public record ProductSnapshotDto(
    Long id,
    String code,
    String name,
    BigDecimal sellingPrice,
    Integer quantityInStock
) {}
