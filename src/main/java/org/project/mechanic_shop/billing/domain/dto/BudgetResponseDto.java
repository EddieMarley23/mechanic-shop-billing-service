package org.project.mechanic_shop.billing.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Customer's response to the service budget")
public record BudgetResponseDto(
	@Schema(description = "true = approve and proceed to payment; false = reject and cancel the order", example = "true")
	@NotNull(message = "The 'approved' field cannot be null") Boolean approved
) {}
