package com.application.dto;

public record InventoryApiResponse<T>(
	    boolean success,
	    String message,
	    T data
	) {}