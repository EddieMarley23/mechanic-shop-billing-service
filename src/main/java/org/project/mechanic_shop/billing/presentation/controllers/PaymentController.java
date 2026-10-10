package org.project.mechanic_shop.billing.presentation.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.billing.application.services.PaymentService;
import org.project.mechanic_shop.billing.domain.dto.ApiResponse;
import org.project.mechanic_shop.billing.domain.dto.PaymentDto;
import org.project.mechanic_shop.billing.domain.dto.PaymentSimulationDto;
import org.project.mechanic_shop.billing.domain.entities.payment.Payment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing/payments")
@RequiredArgsConstructor
@Tag(name = "Payments")
@Slf4j
public class PaymentController {

	private final PaymentService service;

	private static final String SUCCESS_MESSAGE = "success";

	@Operation(summary = "Find the payment of a service order", description = "Returns the payment status and the checkout URL.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payment found")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Access denied")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Payment not created yet")
	@GetMapping("/{serviceOrderId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'RECEPTIONIST', 'CUSTOMER')")
	public ResponseEntity<ApiResponse> findByServiceOrder(@PathVariable UUID serviceOrderId, Authentication authentication) {
		log.info("Find payment by Service Order: {}", serviceOrderId);

		Payment payment = service.findByServiceOrderId(serviceOrderId, RequesterResolver.from(authentication));

		return ResponseEntity.ok(new ApiResponse(HttpStatus.OK.value(), SUCCESS_MESSAGE, PaymentDto.from(payment)));
	}

	@Operation(
		summary = "Simulate the gateway's answer for a pending payment",
		description = "Only available with the simulated gateway (billing.payment.provider=simulated). Plays the role of the payment gateway webhook: approves or rejects the pending payment."
	)
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payment settled")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Access denied")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Payment not found")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "A real payment gateway is configured")
	@PostMapping("/{serviceOrderId}/simulate")
	@PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
	public ResponseEntity<ApiResponse> simulate(
		@PathVariable UUID serviceOrderId,
		@Valid @RequestBody PaymentSimulationDto dto,
		Authentication authentication
	) {
		log.info("Action triggered: Simulating payment for OS {}. Approved: {}", serviceOrderId, dto.approved());

		Payment payment = service.simulate(serviceOrderId, dto.approved(), RequesterResolver.from(authentication));

		String message = dto.approved()
			? "Payment confirmed. The service order will be sent to execution."
			: "Payment rejected. The service order will be CANCELED.";

		return ResponseEntity.ok(new ApiResponse(HttpStatus.OK.value(), message, PaymentDto.from(payment)));
	}
}
