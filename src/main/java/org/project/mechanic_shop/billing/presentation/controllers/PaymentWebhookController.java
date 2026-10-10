package org.project.mechanic_shop.billing.presentation.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGateway;
import org.project.mechanic_shop.billing.application.services.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing/webhooks")
@RequiredArgsConstructor
@Tag(name = "Payment webhooks")
@Slf4j
public class PaymentWebhookController {

	private final PaymentGateway paymentGateway;
	private final PaymentService paymentService;

	@Operation(
		summary = "Mercado Pago payment notifications",
		description = "Public endpoint called by Mercado Pago. The request is authenticated by its signature, verified by the gateway adapter."
	)
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Notification acknowledged")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "Notification refused: invalid signature or gateway not available")
	@PostMapping("/mercadopago")
	public ResponseEntity<Void> mercadoPagoNotification(
		HttpServletRequest request,
		@RequestParam Map<String, String> queryParams,
		@RequestBody(required = false) String rawBody
	) {
		log.info("Payment gateway notification received.");

		Map<String, String> headers = new HashMap<>();
		for (String name : Collections.list(request.getHeaderNames())) {
			headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
		}

		paymentGateway
			.parseNotification(headers, queryParams, rawBody != null ? rawBody : "")
			.ifPresent(paymentService::applyNotification);

		return ResponseEntity.ok().build();
	}
}
