package org.project.mechanic_shop.billing.domain.dto;

public record ApiResponse(int status, String message, Object data) {}
