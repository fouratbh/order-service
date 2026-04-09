package com.application.dto;

public record StockCheckDto(
	    Long productId,
	    Integer quantityInStock,
	    boolean available
	) {}