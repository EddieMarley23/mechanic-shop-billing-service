package org.project.mechanic_shop.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.billing.application.saga.GenerateBudgetPayload;
import org.project.mechanic_shop.billing.application.saga.SagaCommandHandler;
import org.project.mechanic_shop.billing.application.saga.SagaMessage;
import org.project.mechanic_shop.billing.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.billing.application.services.BudgetService;
import org.project.mechanic_shop.billing.application.services.PaymentService;
import org.project.mechanic_shop.billing.application.services.Requester;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.project.mechanic_shop.billing.domain.entities.payment.Payment;
import org.project.mechanic_shop.billing.domain.enums.BudgetItemTypeEnum;
import org.project.mechanic_shop.billing.domain.enums.BudgetStatusEnum;
import org.project.mechanic_shop.billing.domain.enums.PaymentStatusEnum;
import org.project.mechanic_shop.billing.infrastructure.jpa.entities.OutboxMessageJpaEntity;
import org.project.mechanic_shop.billing.infrastructure.jpa.repositories.OutboxMessageJpaRepository;
import org.project.mechanic_shop.billing.infrastructure.jpa.repositories.ProcessedMessageJpaRepository;
import org.project.mechanic_shop.billing.infrastructure.messaging.OutboxRelay;
import org.project.mechanic_shop.billing.infrastructure.messaging.RabbitTopologyConfig;
import org.project.mechanic_shop.billing.infrastructure.messaging.SagaCommandListener;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole application wired together against an in-memory database: transactions, JPA mappings,
 * the outbox and the security filter chain. Only the broker connection is replaced.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillingApplicationTests {

	private static final Requester CUSTOMER = new Requester("cliente@teste.com", "CUSTOMER");

	@Autowired
	private SagaCommandHandler commandHandler;

	@Autowired
	private SagaCommandListener commandListener;

	@Autowired
	private BudgetService budgetService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private OutboxMessageJpaRepository outboxRepository;

	@Autowired
	private ProcessedMessageJpaRepository processedMessageRepository;

	@Autowired
	private OutboxRelay outboxRelay;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RabbitTemplate rabbitTemplate;

	private UUID sagaId;
	private UUID serviceOrderId;

	@BeforeEach
	void setup() {
		outboxRepository.deleteAll();
		sagaId = UUID.randomUUID();
		serviceOrderId = UUID.randomUUID();
	}

	private SagaMessage command(String type, Object payload) {
		return new SagaMessage(
			UUID.randomUUID(),
			sagaId,
			serviceOrderId,
			type,
			Instant.now(),
			"corr-it",
			objectMapper.valueToTree(payload)
		);
	}

	private GenerateBudgetPayload budgetPayload() {
		return new GenerateBudgetPayload(
			"Cliente Teste",
			"cliente@teste.com",
			"ABC1D23",
			List.of(
				new GenerateBudgetPayload.Item(
					BudgetItemTypeEnum.LABOR,
					UUID.randomUUID(),
					"Troca de pastilhas",
					1,
					new BigDecimal("180.00"),
					new BigDecimal("180.00")
				),
				new GenerateBudgetPayload.Item(
					BudgetItemTypeEnum.PART,
					UUID.randomUUID(),
					"Pastilha de freio",
					2,
					new BigDecimal("120.00"),
					new BigDecimal("240.00")
				)
			),
			new BigDecimal("420.00"),
			1
		);
	}

	private List<String> outboxTypes() {
		return outboxRepository.findAll().stream().map(OutboxMessageJpaEntity::getMessageType).toList();
	}

	@Test
	void shouldPersistTheBudgetAndItsReplyInTheOutboxAndIgnoreTheRedelivery() {
		SagaMessage generate = command(SagaMessageTypes.BUDGET_GENERATE, budgetPayload());
		Message delivery = MessageBuilder.withBody(objectMapper.writeValueAsBytes(generate)).build();

		// Through the broker listener, as in production: it is what carries the correlation id over.
		commandListener.onCommand(delivery);
		commandListener.onCommand(delivery);

		Budget budget = budgetService.findByServiceOrderId(serviceOrderId, CUSTOMER);
		assertThat(budget.getStatus()).isEqualTo(BudgetStatusEnum.SENT);
		assertThat(budget.getTotalAmount()).isEqualByComparingTo("420.00");
		assertThat(budget.getItems()).extracting("description").containsExactly("Troca de pastilhas", "Pastilha de freio");
		assertThat(processedMessageRepository.existsById(generate.messageId())).isTrue();

		assertThat(outboxRepository.findAll())
			.singleElement()
			.satisfies(outbox -> {
				assertThat(outbox.getMessageType()).isEqualTo(SagaMessageTypes.BUDGET_SENT);
				assertThat(outbox.getExchange()).isEqualTo(RabbitTopologyConfig.EVENTS_EXCHANGE);
				assertThat(outbox.getPayload()).contains(sagaId.toString()).contains("\"correlationId\":\"corr-it\"");
				assertThat(outbox.getPublishedAt()).isNull();
			});
	}

	@Test
	void shouldGoFromBudgetToConfirmedPaymentAndRefund() {
		commandHandler.handle(command(SagaMessageTypes.BUDGET_GENERATE, budgetPayload()));
		budgetService.respond(serviceOrderId, true, CUSTOMER);
		commandHandler.handle(command(SagaMessageTypes.PAYMENT_CREATE, Map.of()));

		Payment confirmed = paymentService.simulate(serviceOrderId, true, CUSTOMER);
		assertThat(confirmed.getStatus()).isEqualTo(PaymentStatusEnum.CONFIRMED);

		commandHandler.handle(command(SagaMessageTypes.PAYMENT_REFUND, Map.of("reason", "Reparo inviável")));
		commandHandler.handle(command(SagaMessageTypes.BUDGET_CANCEL, Map.of("reason", "Reparo inviável")));

		assertThat(paymentService.findByServiceOrderId(serviceOrderId, CUSTOMER).getStatus()).isEqualTo(
			PaymentStatusEnum.REFUNDED
		);
		assertThat(budgetService.findByServiceOrderId(serviceOrderId, CUSTOMER).getStatus()).isEqualTo(
			BudgetStatusEnum.CANCELED
		);
		assertThat(outboxTypes()).containsExactlyInAnyOrder(
			SagaMessageTypes.BUDGET_SENT,
			SagaMessageTypes.BUDGET_APPROVED,
			SagaMessageTypes.PAYMENT_CREATED,
			SagaMessageTypes.PAYMENT_CONFIRMED,
			SagaMessageTypes.PAYMENT_REFUNDED
		);
	}

	@Test
	void shouldRelayTheOutboxToTheBrokerOnlyOnce() {
		commandHandler.handle(command(SagaMessageTypes.BUDGET_GENERATE, budgetPayload()));

		outboxRelay.relayPending();
		outboxRelay.relayPending();

		verify(rabbitTemplate, times(1)).send(
			eq(RabbitTopologyConfig.EVENTS_EXCHANGE),
			eq(SagaMessageTypes.BUDGET_SENT),
			any(Message.class)
		);
		assertThat(outboxRepository.findAll()).allSatisfy(outbox -> assertThat(outbox.getPublishedAt()).isNotNull());
	}

	@Test
	void shouldRequireAuthenticationExceptOnThePublicEndpoints() throws Exception {
		mockMvc.perform(get("/api/billing/budgets/{id}", serviceOrderId)).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/billing/payments/{id}", serviceOrderId)).andExpect(status().isForbidden());
		mockMvc
			.perform(post("/api/billing/payments/{id}/simulate", serviceOrderId).contentType("application/json").content("{}"))
			.andExpect(status().isForbidden());

		// Public: guarded by the single-use token / by the gateway signature instead of a JWT.
		mockMvc
			.perform(get("/api/billing/budgets/approval").param("token", "unknown").param("approved", "true"))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/billing/webhooks/mercadopago")).andExpect(status().isBadGateway());
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}
}
