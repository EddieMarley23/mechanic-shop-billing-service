package org.project.mechanic_shop.billing.support;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.project.mechanic_shop.billing.application.ports.BudgetRepositoryPort;
import org.project.mechanic_shop.billing.application.ports.PaymentRepositoryPort;
import org.project.mechanic_shop.billing.application.ports.ProcessedMessagePort;
import org.project.mechanic_shop.billing.application.ports.SagaEventPublisher;
import org.project.mechanic_shop.billing.application.ports.payment.ChargeRequest;
import org.project.mechanic_shop.billing.application.ports.payment.ChargeResult;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayNotification;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGateway;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGatewayException;
import org.project.mechanic_shop.billing.application.saga.GenerateBudgetPayload;
import org.project.mechanic_shop.billing.application.saga.SagaCommandHandler;
import org.project.mechanic_shop.billing.application.saga.SagaMessage;
import org.project.mechanic_shop.billing.application.services.Requester;
import org.project.mechanic_shop.billing.application.services.impl.BudgetServiceImpl;
import org.project.mechanic_shop.billing.application.services.impl.PaymentServiceImpl;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.project.mechanic_shop.billing.domain.entities.payment.Payment;
import org.project.mechanic_shop.billing.domain.enums.BudgetItemTypeEnum;
import org.project.mechanic_shop.billing.domain.enums.PaymentProviderEnum;
import org.project.mechanic_shop.billing.shared.config.observability.ObservabilityReporter;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Billing use cases wired to in-memory ports. The real services and the real command handler
 * run; only the database, the broker and the payment gateway are replaced. Used by the unit tests
 * and by the BDD steps, which play the role of the Saga orchestrator by sending its commands.
 */
public class BillingTestHarness {

	public static final Requester ADMIN = new Requester("admin@shop.com", "ADMIN");
	public static final String CUSTOMER_EMAIL = "cliente@teste.com";
	public static final Requester CUSTOMER = new Requester(CUSTOMER_EMAIL, "CUSTOMER");
	public static final Requester OTHER_CUSTOMER = new Requester("outro@teste.com", "CUSTOMER");

	/** A reply this service wrote to the outbox. */
	public record PublishedEvent(UUID sagaId, UUID serviceOrderId, String type, Map<String, Object> payload) {}

	public final MutableClock clock = new MutableClock(Instant.parse("2026-01-10T12:00:00Z"));
	public final ObjectMapper objectMapper = JsonMapper.builder().build();
	public final List<PublishedEvent> sagaEvents = new ArrayList<>();
	public final List<Object> applicationEvents = new ArrayList<>();
	public final InMemoryBudgetRepository budgetRepository = new InMemoryBudgetRepository();
	public final InMemoryPaymentRepository paymentRepository = new InMemoryPaymentRepository();
	public final FakePaymentGateway gateway = new FakePaymentGateway();

	public final BudgetServiceImpl budgetService;
	public final PaymentServiceImpl paymentService;
	public final SagaCommandHandler commandHandler;

	public final UUID sagaId = UUID.randomUUID();
	public final UUID serviceOrderId = UUID.randomUUID();

	private final Set<UUID> processedMessages = new HashSet<>();

	@SuppressWarnings("unchecked")
	public BillingTestHarness() {
		ApplicationEventPublisher eventPublisher = applicationEvents::add;
		SagaEventPublisher sagaEventPublisher = (saga, order, type, payload) ->
			sagaEvents.add(new PublishedEvent(saga, order, type, objectMapper.convertValue(payload, Map.class)));
		ProcessedMessagePort processedMessagePort = (messageId, type) -> processedMessages.add(messageId);

		budgetService = new BudgetServiceImpl(budgetRepository, sagaEventPublisher, eventPublisher, clock);
		paymentService = new PaymentServiceImpl(
			paymentRepository,
			budgetRepository,
			gateway,
			sagaEventPublisher,
			eventPublisher,
			new ObservabilityReporter(),
			clock
		);
		commandHandler = new SagaCommandHandler(
			processedMessagePort,
			budgetService,
			paymentService,
			sagaEventPublisher,
			objectMapper
		);
	}

	// ------------------------------------------------------------------------------------ fixtures

	/** A budget request for one labor of R$ 180.00 and two parts of R$ 120.00: R$ 420.00. */
	public GenerateBudgetPayload budgetPayload() {
		return new GenerateBudgetPayload(
			"Cliente Teste",
			CUSTOMER_EMAIL,
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

	public SagaMessage command(String type, Object payload) {
		return new SagaMessage(
			UUID.randomUUID(),
			sagaId,
			serviceOrderId,
			type,
			clock.instant(),
			"correlation-test",
			objectMapper.valueToTree(payload)
		);
	}

	public void receive(String type, Object payload) {
		commandHandler.handle(command(type, payload));
	}

	public void receive(String type) {
		receive(type, Map.of());
	}

	// ----------------------------------------------------------------------------------- inquiries

	public Budget budget() {
		return budgetRepository.findByServiceOrderId(serviceOrderId).orElseThrow();
	}

	public Payment payment() {
		return paymentRepository.findByServiceOrderId(serviceOrderId).orElseThrow();
	}

	public List<String> sagaEventTypes() {
		return sagaEvents.stream().map(PublishedEvent::type).toList();
	}

	public PublishedEvent lastEvent(String type) {
		return sagaEvents
			.reversed()
			.stream()
			.filter(e -> e.type().equals(type))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Event not published: " + type + ". Published: " + sagaEventTypes()));
	}

	// ------------------------------------------------------------------------------ in-memory ports

	public static class MutableClock extends java.time.Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		public void advance(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public java.time.Clock withZone(ZoneId zone) {
			return this;
		}
	}

	public static class InMemoryBudgetRepository implements BudgetRepositoryPort {

		private final Map<UUID, Budget> budgets = new HashMap<>();

		@Override
		public Budget save(Budget budget) {
			budgets.put(budget.getId(), budget);
			return budget;
		}

		@Override
		public Optional<Budget> findById(UUID id) {
			return Optional.ofNullable(budgets.get(id));
		}

		@Override
		public Optional<Budget> findByServiceOrderId(UUID serviceOrderId) {
			return budgets.values().stream().filter(b -> b.getServiceOrderId().equals(serviceOrderId)).findFirst();
		}

		@Override
		public Optional<Budget> findByApprovalToken(String approvalToken) {
			return budgets.values().stream().filter(b -> approvalToken.equals(b.getApprovalToken())).findFirst();
		}

		public int count() {
			return budgets.size();
		}
	}

	public static class InMemoryPaymentRepository implements PaymentRepositoryPort {

		private final Map<UUID, Payment> payments = new HashMap<>();

		@Override
		public Payment save(Payment payment) {
			payments.put(payment.getId(), payment);
			return payment;
		}

		@Override
		public Optional<Payment> findById(UUID id) {
			return Optional.ofNullable(payments.get(id));
		}

		@Override
		public Optional<Payment> findByServiceOrderId(UUID serviceOrderId) {
			return payments.values().stream().filter(p -> p.getServiceOrderId().equals(serviceOrderId)).findFirst();
		}

		public int count() {
			return payments.size();
		}
	}

	/** A gateway whose behaviour each test decides. */
	public static class FakePaymentGateway implements PaymentGateway {

		public PaymentProviderEnum provider = PaymentProviderEnum.SIMULATED;
		public boolean refuseCharges;
		public boolean refuseRefunds;
		public final List<ChargeRequest> charges = new ArrayList<>();
		public final List<String> refundedPaymentIds = new ArrayList<>();

		@Override
		public PaymentProviderEnum provider() {
			return provider;
		}

		@Override
		public ChargeResult createCharge(ChargeRequest request) {
			if (refuseCharges) throw new PaymentGatewayException("Gateway unavailable");
			charges.add(request);
			return new ChargeResult("REF-" + request.paymentId(), "https://pay.test/" + request.paymentId());
		}

		@Override
		public Optional<GatewayNotification> parseNotification(
			Map<String, String> headers,
			Map<String, String> queryParams,
			String rawBody
		) {
			return Optional.empty();
		}

		@Override
		public void refund(String providerPaymentId, BigDecimal amount, String idempotencyKey) {
			if (refuseRefunds) throw new PaymentGatewayException("Refund refused");
			refundedPaymentIds.add(providerPaymentId);
		}
	}
}
