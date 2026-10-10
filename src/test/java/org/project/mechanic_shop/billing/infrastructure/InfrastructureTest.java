package org.project.mechanic_shop.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.project.mechanic_shop.billing.application.listeners.BillingNotificationListener;
import org.project.mechanic_shop.billing.application.ports.EmailService;
import org.project.mechanic_shop.billing.application.ports.payment.ChargeRequest;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGatewayException;
import org.project.mechanic_shop.billing.application.saga.SagaCommandHandler;
import org.project.mechanic_shop.billing.application.saga.SagaMessage;
import org.project.mechanic_shop.billing.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.project.mechanic_shop.billing.domain.entities.budget.BudgetItem;
import org.project.mechanic_shop.billing.domain.entities.payment.Payment;
import org.project.mechanic_shop.billing.domain.enums.BudgetItemTypeEnum;
import org.project.mechanic_shop.billing.domain.enums.PaymentProviderEnum;
import org.project.mechanic_shop.billing.domain.events.BudgetSentEvent;
import org.project.mechanic_shop.billing.domain.events.PaymentCreatedEvent;
import org.project.mechanic_shop.billing.infrastructure.adapters.OutboxSagaEventPublisher;
import org.project.mechanic_shop.billing.infrastructure.adapters.ProcessedMessageAdapter;
import org.project.mechanic_shop.billing.infrastructure.email.MockEmailServiceImpl;
import org.project.mechanic_shop.billing.infrastructure.email.SmtpEmailServiceImpl;
import org.project.mechanic_shop.billing.infrastructure.jpa.entities.OutboxMessageJpaEntity;
import org.project.mechanic_shop.billing.infrastructure.jpa.repositories.OutboxMessageJpaRepository;
import org.project.mechanic_shop.billing.infrastructure.jpa.repositories.ProcessedMessageJpaRepository;
import org.project.mechanic_shop.billing.infrastructure.messaging.OutboxRelay;
import org.project.mechanic_shop.billing.infrastructure.messaging.RabbitTopologyConfig;
import org.project.mechanic_shop.billing.infrastructure.messaging.SagaCommandListener;
import org.project.mechanic_shop.billing.infrastructure.payment.MercadoPagoPaymentGateway;
import org.project.mechanic_shop.billing.infrastructure.payment.MercadoPagoProperties;
import org.project.mechanic_shop.billing.infrastructure.payment.SimulatedPaymentGateway;
import org.project.mechanic_shop.billing.shared.config.observability.ObservabilityReporter;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class InfrastructureTest {

	private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");
	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	@Nested
	class SimulatedGateway {

		private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();

		private ChargeRequest charge(String amount) {
			return new ChargeRequest(
				UUID.fromString("11111111-1111-1111-1111-111111111111"),
				UUID.fromString("22222222-2222-2222-2222-222222222222"),
				amount != null ? new BigDecimal(amount) : null,
				"OS",
				"Cliente",
				"c@t.com"
			);
		}

		@Test
		void shouldAcceptTheChargeAndPointToTheSimulationEndpoint() {
			ReflectionTestUtils.setField(gateway, "baseUrl", "http://localhost:8000");

			var result = gateway.createCharge(charge("420.00"));

			assertThat(gateway.provider()).isEqualTo(PaymentProviderEnum.SIMULATED);
			assertThat(result.providerReference()).isEqualTo("SIM-11111111-1111-1111-1111-111111111111");
			assertThat(result.checkoutUrl()).isEqualTo(
				"http://localhost:8000/api/billing/payments/22222222-2222-2222-2222-222222222222/simulate"
			);
		}

		@Test
		void shouldRefuseAChargeWithoutAPositiveAmount() {
			assertThatThrownBy(() -> gateway.createCharge(charge("0.00"))).isInstanceOf(PaymentGatewayException.class);
			assertThatThrownBy(() -> gateway.createCharge(charge(null))).isInstanceOf(PaymentGatewayException.class);
		}

		@Test
		void shouldHaveNoWebhookAndAcceptRefunds() {
			assertThatThrownBy(() -> gateway.parseNotification(Map.of(), Map.of(), "{}"))
				.isInstanceOf(PaymentGatewayException.class)
				.hasMessageContaining("/simulate");

			gateway.refund("SIM-PAY-1", new BigDecimal("10.00"), "refund-1");
		}
	}

	@Nested
	class MercadoPagoGateway {

		private MercadoPagoPaymentGateway gateway(String accessToken) {
			return new MercadoPagoPaymentGateway(
				new MercadoPagoProperties(accessToken, "secret", "https://api.mercadopago.com", "https://shop/webhook")
			);
		}

		@Test
		void shouldFailEveryOperationUntilItIsImplemented() {
			var gateway = gateway("TEST-token");
			var charge = new ChargeRequest(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "OS", "Cliente", "c@t.com");

			assertThat(gateway.provider()).isEqualTo(PaymentProviderEnum.MERCADO_PAGO);
			assertThatThrownBy(() -> gateway.createCharge(charge))
				.isInstanceOf(PaymentGatewayException.class)
				.hasMessageContaining("not implemented");
			assertThatThrownBy(() -> gateway.parseNotification(Map.of(), Map.of(), "{}")).isInstanceOf(
				PaymentGatewayException.class
			);
			assertThatThrownBy(() -> gateway.refund("1", BigDecimal.TEN, "k")).isInstanceOf(PaymentGatewayException.class);
		}

		@Test
		void shouldKnowWhetherItsCredentialsAreConfigured() {
			assertThat(gateway("TEST-token").isConfigured()).isTrue();
			assertThat(gateway("").isConfigured()).isFalse();
			assertThat(gateway(null).isConfigured()).isFalse();
		}
	}

	@Nested
	class EventPublisher {

		private final OutboxMessageJpaRepository outboxRepository = mock(OutboxMessageJpaRepository.class);
		private final OutboxSagaEventPublisher publisher = new OutboxSagaEventPublisher(outboxRepository, objectMapper, clock);

		@Test
		void shouldStoreTheEventInTheOutboxRoutedByItsType() {
			UUID sagaId = UUID.randomUUID();
			UUID serviceOrderId = UUID.randomUUID();

			publisher.publish(sagaId, serviceOrderId, SagaMessageTypes.BUDGET_SENT, Map.of("budgetId", "b-1"));

			ArgumentCaptor<OutboxMessageJpaEntity> captor = ArgumentCaptor.forClass(OutboxMessageJpaEntity.class);
			verify(outboxRepository).save(captor.capture());
			OutboxMessageJpaEntity outbox = captor.getValue();
			assertThat(outbox.getExchange()).isEqualTo(RabbitTopologyConfig.EVENTS_EXCHANGE);
			assertThat(outbox.getRoutingKey()).isEqualTo(SagaMessageTypes.BUDGET_SENT);
			assertThat(outbox.getCreatedAt()).isEqualTo(NOW);
			assertThat(outbox.getPublishedAt()).isNull();

			SagaMessage envelope = objectMapper.readValue(outbox.getPayload(), SagaMessage.class);
			assertThat(envelope.messageId()).isEqualTo(outbox.getId());
			assertThat(envelope.sagaId()).isEqualTo(sagaId);
			assertThat(envelope.serviceOrderId()).isEqualTo(serviceOrderId);
			assertThat(envelope.occurredAt()).isEqualTo(NOW);
			assertThat(envelope.payload().get("budgetId").asString()).isEqualTo("b-1");
		}
	}

	@Nested
	class Relay {

		private final OutboxMessageJpaRepository outboxRepository = mock(OutboxMessageJpaRepository.class);
		private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
		private final OutboxRelay relay = new OutboxRelay(outboxRepository, rabbitTemplate, clock);

		private OutboxMessageJpaEntity pending() {
			OutboxMessageJpaEntity outbox = new OutboxMessageJpaEntity();
			outbox.setId(UUID.randomUUID());
			outbox.setExchange(RabbitTopologyConfig.EVENTS_EXCHANGE);
			outbox.setRoutingKey(SagaMessageTypes.BUDGET_SENT);
			outbox.setMessageType(SagaMessageTypes.BUDGET_SENT);
			outbox.setPayload("{\"type\":\"billing.budget.sent\"}");
			outbox.setCreatedAt(NOW);
			return outbox;
		}

		@Test
		void shouldSendPendingMessagesAndMarkThemPublished() {
			ReflectionTestUtils.setField(relay, "batchSize", 50);
			OutboxMessageJpaEntity outbox = pending();
			when(outboxRepository.lockPendingBatch(any(Pageable.class))).thenReturn(List.of(outbox));

			relay.relayPending();

			ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
			verify(rabbitTemplate).send(eq(RabbitTopologyConfig.EVENTS_EXCHANGE), eq(SagaMessageTypes.BUDGET_SENT), captor.capture());
			assertThat(new String(captor.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo(outbox.getPayload());
			assertThat(captor.getValue().getMessageProperties().getMessageId()).isEqualTo(outbox.getId().toString());
			assertThat(outbox.getPublishedAt()).isEqualTo(NOW);
		}

		@Test
		void shouldKeepMessagesPendingWhenTheBrokerIsDown() {
			ReflectionTestUtils.setField(relay, "batchSize", 50);
			OutboxMessageJpaEntity first = pending();
			OutboxMessageJpaEntity second = pending();
			when(outboxRepository.lockPendingBatch(any(Pageable.class))).thenReturn(List.of(first, second));
			doThrow(new AmqpConnectException(new RuntimeException("down")))
				.when(rabbitTemplate)
				.send(any(String.class), any(String.class), any(Message.class));

			relay.relayPending();

			assertThat(first.getPublishedAt()).isNull();
			assertThat(second.getPublishedAt()).isNull();
			verify(rabbitTemplate, times(1)).send(any(String.class), any(String.class), any(Message.class));
		}
	}

	@Nested
	class CommandListener {

		private final SagaCommandHandler handler = mock(SagaCommandHandler.class);
		private final ObservabilityReporter reporter = mock(ObservabilityReporter.class);
		private final SagaCommandListener listener = new SagaCommandListener(handler, objectMapper, reporter);

		private SagaMessage command(String correlationId) {
			return new SagaMessage(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				SagaMessageTypes.PAYMENT_CREATE,
				NOW,
				correlationId,
				objectMapper.valueToTree(Map.of())
			);
		}

		@Test
		void shouldDeserializeTheEnvelopeAndDelegateToTheHandler() {
			SagaMessage command = command("corr-1");

			listener.onCommand(MessageBuilder.withBody(objectMapper.writeValueAsBytes(command)).build());

			ArgumentCaptor<SagaMessage> captor = ArgumentCaptor.forClass(SagaMessage.class);
			verify(handler).handle(captor.capture());
			assertThat(captor.getValue().messageId()).isEqualTo(command.messageId());
			assertThat(captor.getValue().type()).isEqualTo(SagaMessageTypes.PAYMENT_CREATE);
		}

		@Test
		void shouldReportAndRethrowSoTheBrokerRetriesAndDeadLetters() {
			SagaMessage command = command(null);
			IllegalStateException failure = new IllegalStateException("boom");
			doThrow(failure).when(handler).handle(any());

			assertThatThrownBy(() -> listener.onCommand(MessageBuilder.withBody(objectMapper.writeValueAsBytes(command)).build()))
				.isSameAs(failure);

			verify(reporter).recordSagaFailure(command.type(), command.sagaId(), command.serviceOrderId(), failure);
		}
	}

	@Nested
	class ProcessedMessages {

		private final ProcessedMessageJpaRepository repository = mock(ProcessedMessageJpaRepository.class);
		private final ProcessedMessageAdapter adapter = new ProcessedMessageAdapter(repository, clock);

		@Test
		void shouldRegisterNewMessagesAndRefuseRedeliveries() {
			UUID fresh = UUID.randomUUID();
			UUID redelivered = UUID.randomUUID();
			when(repository.existsById(fresh)).thenReturn(false);
			when(repository.existsById(redelivered)).thenReturn(true);

			assertThat(adapter.registerIfNew(fresh, "billing.budget.generate")).isTrue();
			assertThat(adapter.registerIfNew(redelivered, "billing.budget.generate")).isFalse();

			verify(repository, times(1)).saveAndFlush(any());
		}
	}

	@Nested
	class Topology {

		@Test
		void shouldDeclareEveryQueueWithItsDeadLetterQueue() {
			List<Declarable> declarables = List.copyOf(new RabbitTopologyConfig().sagaTopology().getDeclarables());

			List<String> queues = declarables.stream().filter(Queue.class::isInstance).map(d -> ((Queue) d).getName()).toList();
			assertThat(queues).containsExactlyInAnyOrder(
				"billing.commands",
				"billing.commands.dlq",
				"execution.commands",
				"execution.commands.dlq",
				"os.saga-replies",
				"os.saga-replies.dlq"
			);

			Queue commands = declarables
				.stream()
				.filter(Queue.class::isInstance)
				.map(Queue.class::cast)
				.filter(q -> q.getName().equals(RabbitTopologyConfig.BILLING_COMMANDS_QUEUE))
				.findFirst()
				.orElseThrow();
			assertThat(commands.isDurable()).isTrue();
			assertThat(commands.getArguments())
				.containsEntry("x-dead-letter-exchange", RabbitTopologyConfig.DEAD_LETTER_EXCHANGE)
				.containsEntry("x-dead-letter-routing-key", "billing.commands.dlq");
		}
	}

	@Nested
	class Emails {

		private final EmailService emailService = mock(EmailService.class);
		private final BillingNotificationListener listener = new BillingNotificationListener(emailService);

		private Budget budget(String customerEmail) {
			Budget budget = new Budget();
			budget.setServiceOrderId(UUID.randomUUID());
			budget.setCustomerEmail(customerEmail);
			budget.setLicensePlate("ABC1D23");
			budget.setTotalAmount(new BigDecimal("420.00"));
			budget.setItems(
				List.of(
					new BudgetItem(
						BudgetItemTypeEnum.PART,
						UUID.randomUUID(),
						"Pastilha de freio",
						2,
						new BigDecimal("120.00"),
						new BigDecimal("240.00")
					)
				)
			);
			return budget;
		}

		@Test
		void shouldEmailTheBudgetWithItemsAndSingleUseLinks() {
			ReflectionTestUtils.setField(listener, "baseUrl", "http://localhost:8000");

			listener.handleBudgetSent(new BudgetSentEvent(budget("cliente@teste.com"), "tok-1"));

			ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
			verify(emailService).sendEmail(
				eq(new String[] { "cliente@teste.com" }),
				eq("Ação Necessária: Orçamento Pendente de Aprovação"),
				body.capture()
			);
			assertThat(body.getValue())
				.contains("http://localhost:8000/api/billing/budgets/approval?token=tok-1&approved=true")
				.contains("http://localhost:8000/api/billing/budgets/approval?token=tok-1&approved=false")
				.contains("2x Pastilha de freio: R$ 240.00")
				.contains("Total do Orçamento: R$ 420.00");
		}

		@Test
		void shouldEmailThePaymentLink() {
			Payment payment = new Payment();
			payment.setServiceOrderId(UUID.randomUUID());
			payment.setAmount(new BigDecimal("420.00"));
			payment.setCheckoutUrl("https://pay.test/1");

			listener.handlePaymentCreated(new PaymentCreatedEvent(payment, budget("cliente@teste.com")));

			verify(emailService).sendEmail(
				eq(new String[] { "cliente@teste.com" }),
				eq("Pagamento Pendente: Ordem de Serviço"),
				contains("https://pay.test/1")
			);
		}

		@Test
		void shouldNotEmailACustomerWithoutAddress() {
			listener.handleBudgetSent(new BudgetSentEvent(budget(null), "tok-1"));

			verify(emailService, never()).sendEmail(any(), any(), any());
		}

		@Test
		void shouldOnlyLogWithTheMockEmailService() {
			new MockEmailServiceImpl().sendEmail(new String[] { "c@t.com" }, "Assunto", "Corpo");
		}

		@Test
		void shouldSendThroughSmtpRetryingTransientFailures() {
			JavaMailSender mailSender = mock(JavaMailSender.class);
			ObservabilityReporter reporter = mock(ObservabilityReporter.class);
			SmtpEmailServiceImpl smtp = new SmtpEmailServiceImpl(mailSender, reporter);
			ReflectionTestUtils.setField(smtp, "senderEmail", "billing@shop.com");
			doThrow(new MailSendException("temporary")).doNothing().when(mailSender).send(any(SimpleMailMessage.class));

			smtp.sendEmail(new String[] { "c@t.com" }, "Assunto", "Corpo");

			ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
			verify(mailSender, times(2)).send(captor.capture());
			assertThat(captor.getValue().getFrom()).isEqualTo("billing@shop.com");
			assertThat(captor.getValue().getSubject()).isEqualTo("Assunto");
			verify(reporter, never()).recordIntegrationFailure(any(), any(), any());
		}

		@Test
		void shouldReportTheIntegrationFailureWhenSmtpKeepsFailing() {
			JavaMailSender mailSender = mock(JavaMailSender.class);
			ObservabilityReporter reporter = mock(ObservabilityReporter.class);
			SmtpEmailServiceImpl smtp = new SmtpEmailServiceImpl(mailSender, reporter);
			ReflectionTestUtils.setField(smtp, "senderEmail", "billing@shop.com");
			doThrow(new MailSendException("down")).when(mailSender).send(any(SimpleMailMessage.class));

			assertThatThrownBy(() -> smtp.sendEmail(new String[] { "c@t.com" }, "Assunto", "Corpo"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("after 3 attempts");

			verify(mailSender, times(3)).send(any(SimpleMailMessage.class));
			verify(reporter).recordIntegrationFailure(eq("SMTP"), eq("sendEmail"), any(IllegalStateException.class));
		}
	}

	@Nested
	class Observability {

		@Test
		void shouldNeverFailTheCallerWhenReportingTelemetry() {
			ObservabilityReporter reporter = new ObservabilityReporter();

			reporter.recordSagaFailure("billing.payment.create", UUID.randomUUID(), UUID.randomUUID(), new RuntimeException("x"));
			reporter.recordIntegrationFailure("SMTP", "sendEmail", new RuntimeException("x"));
			reporter.recordSagaFailure(null, null, null, new RuntimeException());
		}
	}
}
