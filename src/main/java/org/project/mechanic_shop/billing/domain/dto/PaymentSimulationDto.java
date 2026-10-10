package org.project.mechanic_shop.billing.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Outcome to simulate for a pending payment (only with the simulated gateway)")
public record PaymentSimulationDto(
	@Schema(description = "true = gateway approves the payment; false = gateway rejects it", example = "true")
	@NotNull(message = "The 'approved' field cannot be null") Boolean approved
) {}
