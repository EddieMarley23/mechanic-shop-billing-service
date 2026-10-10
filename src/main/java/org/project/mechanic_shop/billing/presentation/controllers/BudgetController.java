package org.project.mechanic_shop.billing.presentation.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.billing.application.services.BudgetService;
import org.project.mechanic_shop.billing.domain.dto.ApiResponse;
import org.project.mechanic_shop.billing.domain.dto.BudgetDto;
import org.project.mechanic_shop.billing.domain.dto.BudgetResponseDto;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing/budgets")
@RequiredArgsConstructor
@Tag(name = "Budgets")
@Slf4j
public class BudgetController {

	private final BudgetService service;

	private static final String SUCCESS_MESSAGE = "success";

	@Operation(
		summary = "Process budget approval via email link",
		description = "Public endpoint — no authentication required. Called when the customer clicks the approve/reject link in the notification email. Token is single-use."
	)
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Response processed")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Token invalid or already used")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Budget is no longer awaiting a response")
	@GetMapping(value = "/approval", produces = "text/html;charset=UTF-8")
	public ResponseEntity<String> processBudgetApprovalByEmail(
		@RequestParam String token,
		@RequestParam boolean approved
	) {
		log.info("Email budget approval link clicked. Approved: {}", approved);

		service.respondByToken(token, approved);

		String title = approved ? "Orçamento Aprovado!" : "Orçamento Recusado";
		String icon = approved ? "✅" : "❌";
		String msg = approved
			? "Obrigado! Enviaremos o link de pagamento em instantes. O reparo começa assim que o pagamento for confirmado."
			: "Entendemos. A ordem de serviço foi cancelada. Por favor, providencie a retirada do seu veículo.";

		String html =
			"<html><body style='font-family:sans-serif;text-align:center;padding:60px'>" +
			"<h1>" + icon + " " + title + "</h1>" +
			"<p style='font-size:18px'>" + msg + "</p>" +
			"</body></html>";

		MediaType htmlUtf8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);
		return ResponseEntity.ok().contentType(htmlUtf8).body(html);
	}

	@Operation(summary = "Find the budget of a service order")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Budget found")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Access denied")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Budget not generated yet")
	@GetMapping("/{serviceOrderId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'RECEPTIONIST', 'MECHANIC', 'CUSTOMER')")
	public ResponseEntity<ApiResponse> findByServiceOrder(@PathVariable UUID serviceOrderId, Authentication authentication) {
		log.info("Find budget by Service Order: {}", serviceOrderId);

		Budget budget = service.findByServiceOrderId(serviceOrderId, RequesterResolver.from(authentication));

		return ResponseEntity.ok(new ApiResponse(HttpStatus.OK.value(), SUCCESS_MESSAGE, BudgetDto.from(budget)));
	}

	@Operation(
		summary = "Process customer budget response",
		description = "If approved, the Saga reserves the stock and creates the payment. If rejected, the service order is canceled."
	)
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Budget response processed")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Access denied")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Budget not found")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Budget is no longer awaiting a response")
	@PostMapping("/{serviceOrderId}/response")
	@PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
	public ResponseEntity<ApiResponse> processBudgetResponse(
		@PathVariable UUID serviceOrderId,
		@Valid @RequestBody BudgetResponseDto responseDto,
		Authentication authentication
	) {
		log.info("Action triggered: Processing budget response for OS {}. Approved: {}", serviceOrderId, responseDto.approved());

		Budget budget = service.respond(serviceOrderId, responseDto.approved(), RequesterResolver.from(authentication));

		String message = responseDto.approved()
			? "Budget approved successfully! The payment will be created shortly."
			: "Budget rejected. The service order will be CANCELED.";

		return ResponseEntity.ok(new ApiResponse(HttpStatus.OK.value(), message, BudgetDto.from(budget)));
	}
}
