package org.project.mechanic_shop.billing.presentation;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayNotification;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayPaymentStatusEnum;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGateway;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGatewayException;
import org.project.mechanic_shop.billing.application.services.BudgetService;
import org.project.mechanic_shop.billing.application.services.PaymentService;
import org.project.mechanic_shop.billing.application.services.Requester;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.project.mechanic_shop.billing.domain.entities.budget.BudgetItem;
import org.project.mechanic_shop.billing.domain.entities.payment.Payment;
import org.project.mechanic_shop.billing.domain.enums.BudgetItemTypeEnum;
import org.project.mechanic_shop.billing.domain.enums.BudgetStatusEnum;
import org.project.mechanic_shop.billing.domain.enums.PaymentProviderEnum;
import org.project.mechanic_shop.billing.domain.enums.PaymentStatusEnum;
import org.project.mechanic_shop.billing.presentation.controllers.BudgetController;
import org.project.mechanic_shop.billing.presentation.controllers.PaymentController;
import org.project.mechanic_shop.billing.presentation.controllers.PaymentWebhookController;
import org.project.mechanic_shop.billing.presentation.middlewares.GlobalExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controllers with their exception handler, without the security filter chain: what is checked here
 * is the HTTP contract and that the caller's identity reaches the use cases.
 */
class BillingControllersTest {

	private static final UUID SERVICE_ORDER_ID = UUID.randomUUID();
	private static final Authentication CUSTOMER = new UsernamePasswordAuthenticationToken(
		"cliente@teste.com",
		null,
		List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
	);

	private final BudgetService budgetService = mock(BudgetService.class);
	private final PaymentService paymentService = mock(PaymentService.class);
	private final PaymentGateway paymentGateway = mock(PaymentGateway.class);
	private MockMvc mockMvc;

	@BeforeEach
	void setup() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(
				new BudgetController(budgetService),
				new PaymentController(paymentService),
				new PaymentWebhookController(paymentGateway, paymentService)
			)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	private Budget budget(BudgetStatusEnum status) {
		Budget budget = new Budget();
		budget.setId(UUID.randomUUID());
		budget.setServiceOrderId(SERVICE_ORDER_ID);
		budget.setCustomerName("Cliente Teste");
		budget.setCustomerEmail("cliente@teste.com");
		budget.setLicensePlate("ABC1D23");
		budget.setTotalAmount(new BigDecimal("420.00"));
		budget.setStatus(status);
		budget.setApprovalToken("secret-token");
		budget.setCreatedAt(Instant.parse("2026-01-10T12:00:00Z"));
		budget.setItems(
			List.of(
				new BudgetItem(
					BudgetItemTypeEnum.LABOR,
					UUID.randomUUID(),
					"Troca de pastilhas",
					1,
					new BigDecimal("180.00"),
					new BigDecimal("180.00")
				)
			)
		);
		return budget;
	}

	private Payment payment(PaymentStatusEnum status) {
		Payment payment = new Payment();
		payment.setId(UUID.randomUUID());
		payment.setServiceOrderId(SERVICE_ORDER_ID);
		payment.setBudgetId(UUID.randomUUID());
		payment.setAmount(new BigDecimal("420.00"));
		payment.setStatus(status);
		payment.setProvider(PaymentProviderEnum.SIMULATED);
		payment.setCheckoutUrl("https://pay.test/1");
		return payment;
	}

	@Nested
	class Budgets {

		@Test
		void shouldReturnTheBudgetWithoutLeakingTheApprovalToken() throws Exception {
			when(budgetService.findByServiceOrderId(eq(SERVICE_ORDER_ID), any())).thenReturn(budget(BudgetStatusEnum.SENT));

			mockMvc
				.perform(get("/api/billing/budgets/{id}", SERVICE_ORDER_ID).principal(CUSTOMER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("success"))
				.andExpect(jsonPath("$.data.status").value("SENT"))
				.andExpect(jsonPath("$.data.totalAmount").value(420.00))
				.andExpect(jsonPath("$.data.items[0].description").value("Troca de pastilhas"))
				.andExpect(jsonPath("$.data.approvalToken").doesNotExist())
				.andExpect(jsonPath("$.data.customerEmail").doesNotExist());

			ArgumentCaptor<Requester> requester = ArgumentCaptor.forClass(Requester.class);
			verify(budgetService).findByServiceOrderId(eq(SERVICE_ORDER_ID), requester.capture());
			org.assertj.core.api.Assertions.assertThat(requester.getValue()).isEqualTo(
				new Requester("cliente@teste.com", "CUSTOMER")
			);
		}

		@Test
		void shouldReturnNotFoundWhenThereIsNoBudget() throws Exception {
			when(budgetService.findByServiceOrderId(any(), any())).thenThrow(new EntityNotFoundException("Budget not found"));

			mockMvc
				.perform(get("/api/billing/budgets/{id}", SERVICE_ORDER_ID).principal(CUSTOMER))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Budget not found"));
		}

		@Test
		void shouldReturnForbiddenWhenTheBudgetBelongsToSomeoneElse() throws Exception {
			when(budgetService.findByServiceOrderId(any(), any())).thenThrow(new AccessDeniedException("nope"));

			mockMvc
				.perform(get("/api/billing/budgets/{id}", SERVICE_ORDER_ID).principal(CUSTOMER))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Access denied."));
		}

		@Test
		void shouldProcessTheCustomerApproval() throws Exception {
			when(budgetService.respond(eq(SERVICE_ORDER_ID), eq(true), any())).thenReturn(budget(BudgetStatusEnum.APPROVED));

			mockMvc
				.perform(
					post("/api/billing/budgets/{id}/response", SERVICE_ORDER_ID)
						.principal(CUSTOMER)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"approved\": true}")
				)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(containsString("approved")))
				.andExpect(jsonPath("$.data.status").value("APPROVED"));
		}

		@Test
		void shouldProcessTheCustomerRejection() throws Exception {
			when(budgetService.respond(eq(SERVICE_ORDER_ID), eq(false), any())).thenReturn(budget(BudgetStatusEnum.REJECTED));

			mockMvc
				.perform(
					post("/api/billing/budgets/{id}/response", SERVICE_ORDER_ID)
						.principal(CUSTOMER)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"approved\": false}")
				)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(containsString("rejected")));
		}

		@Test
		void shouldValidateTheResponsePayload() throws Exception {
			mockMvc
				.perform(
					post("/api/billing/budgets/{id}/response", SERVICE_ORDER_ID)
						.principal(CUSTOMER)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}")
				)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.errors[0].field").value("approved"));

			verify(budgetService, never()).respond(any(), any(Boolean.class), any());
		}

		@Test
		void shouldRejectMalformedJson() throws Exception {
			mockMvc
				.perform(
					post("/api/billing/budgets/{id}/response", SERVICE_ORDER_ID)
						.principal(CUSTOMER)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{not json")
				)
				.andExpect(status().isBadRequest());
		}

		@Test
		void shouldReturnConflictWhenTheBudgetWasAlreadyAnswered() throws Exception {
			when(budgetService.respond(any(), any(Boolean.class), any())).thenThrow(
				new IllegalStateException("Budget is no longer awaiting a response.")
			);

			mockMvc
				.perform(
					post("/api/billing/budgets/{id}/response", SERVICE_ORDER_ID)
						.principal(CUSTOMER)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"approved\": true}")
				)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Budget is no longer awaiting a response."));
		}

		@Test
		void shouldShowAnApprovalPageForTheEmailLink() throws Exception {
			mockMvc
				.perform(get("/api/billing/budgets/approval").param("token", "abc").param("approved", "true"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Orçamento Aprovado!")));

			verify(budgetService).respondByToken("abc", true);
		}

		@Test
		void shouldShowARejectionPageForTheEmailLink() throws Exception {
			mockMvc
				.perform(get("/api/billing/budgets/approval").param("token", "abc").param("approved", "false"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Orçamento Recusado")));

			verify(budgetService).respondByToken("abc", false);
		}

		@Test
		void shouldRequireTheTokenOnTheEmailLink() throws Exception {
			mockMvc.perform(get("/api/billing/budgets/approval").param("approved", "true")).andExpect(status().isBadRequest());
		}
	}

	@Nested
	class Payments {

		@Test
		void shouldReturnThePaymentWithItsCheckoutUrl() throws Exception {
			when(paymentService.findByServiceOrderId(eq(SERVICE_ORDER_ID), any())).thenReturn(
				payment(PaymentStatusEnum.PENDING)
			);

			mockMvc
				.perform(get("/api/billing/payments/{id}", SERVICE_ORDER_ID).principal(CUSTOMER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("PENDING"))
				.andExpect(jsonPath("$.data.provider").value("SIMULATED"))
				.andExpect(jsonPath("$.data.checkoutUrl").value("https://pay.test/1"));
		}

		@Test
		void shouldSimulateAnApprovedPayment() throws Exception {
			when(paymentService.simulate(eq(SERVICE_ORDER_ID), eq(true), any())).thenReturn(
				payment(PaymentStatusEnum.CONFIRMED)
			);

			mockMvc
				.perform(
					post("/api/billing/payments/{id}/simulate", SERVICE_ORDER_ID)
						.principal(CUSTOMER)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"approved\": true}")
				)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(containsString("confirmed")))
				.andExpect(jsonPath("$.data.status").value("CONFIRMED"));
		}

		@Test
		void shouldSimulateARejectedPayment() throws Exception {
			when(paymentService.simulate(eq(SERVICE_ORDER_ID), eq(false), any())).thenReturn(payment(PaymentStatusEnum.FAILED));

			mockMvc
				.perform(
					post("/api/billing/payments/{id}/simulate", SERVICE_ORDER_ID)
						.principal(CUSTOMER)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"approved\": false}")
				)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(containsString("rejected")));
		}

		@Test
		void shouldReturnConflictWhenSimulationIsNotAvailable() throws Exception {
			when(paymentService.simulate(any(), any(Boolean.class), any())).thenThrow(
				new IllegalStateException("Payment simulation is only available with the simulated gateway.")
			);

			mockMvc
				.perform(
					post("/api/billing/payments/{id}/simulate", SERVICE_ORDER_ID)
						.principal(CUSTOMER)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"approved\": true}")
				)
				.andExpect(status().isConflict());
		}

		@Test
		void shouldRejectAnInvalidServiceOrderId() throws Exception {
			mockMvc.perform(get("/api/billing/payments/{id}", "not-a-uuid").principal(CUSTOMER)).andExpect(status().isBadRequest());
		}

		@Test
		void shouldHideUnexpectedErrors() throws Exception {
			when(paymentService.findByServiceOrderId(any(), any())).thenThrow(new RuntimeException("db password is hunter2"));

			mockMvc
				.perform(get("/api/billing/payments/{id}", SERVICE_ORDER_ID).principal(CUSTOMER))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.message").value("Unexpected error: Contact the technical team."));
		}
	}

	@Nested
	class Webhook {

		@Test
		void shouldApplyAVerifiedNotification() throws Exception {
			var notification = new GatewayNotification(UUID.randomUUID(), "GW-1", GatewayPaymentStatusEnum.APPROVED, null);
			when(paymentGateway.parseNotification(anyMap(), anyMap(), eq("{\"data\":{\"id\":\"1\"}}"))).thenReturn(
				Optional.of(notification)
			);

			mockMvc
				.perform(
					post("/api/billing/webhooks/mercadopago")
						.queryParam("type", "payment")
						.header("X-Signature", "ts=1,v1=abc")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"data\":{\"id\":\"1\"}}")
				)
				.andExpect(status().isOk());

			verify(paymentService).applyNotification(notification);

			@SuppressWarnings("unchecked")
			ArgumentCaptor<java.util.Map<String, String>> headers = ArgumentCaptor.forClass(java.util.Map.class);
			verify(paymentGateway).parseNotification(headers.capture(), anyMap(), any());
			org.assertj.core.api.Assertions.assertThat(headers.getValue()).containsEntry("x-signature", "ts=1,v1=abc");
		}

		@Test
		void shouldAcknowledgeNotificationsThatAreNotAboutPayments() throws Exception {
			when(paymentGateway.parseNotification(anyMap(), anyMap(), any())).thenReturn(Optional.empty());

			mockMvc.perform(post("/api/billing/webhooks/mercadopago")).andExpect(status().isOk());

			verify(paymentService, never()).applyNotification(any());
		}

		@Test
		void shouldAnswerUnauthorizedToAForgedSignature() throws Exception {
			when(paymentGateway.parseNotification(anyMap(), anyMap(), any())).thenThrow(
				new org.project.mechanic_shop.billing.application.ports.payment.InvalidWebhookSignatureException("Invalid x-signature.")
			);

			mockMvc
				.perform(post("/api/billing/webhooks/mercadopago").content("{}").contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid x-signature."));

			verify(paymentService, never()).applyNotification(any());
		}

		@Test
		void shouldRefuseANotificationTheGatewayAdapterCannotVerify() throws Exception {
			when(paymentGateway.parseNotification(anyMap(), anyMap(), any())).thenThrow(
				new PaymentGatewayException("Invalid signature")
			);

			mockMvc
				.perform(post("/api/billing/webhooks/mercadopago").content("{}").contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.message").value("Invalid signature"));

			verify(paymentService, never()).applyNotification(any());
		}
	}
}
