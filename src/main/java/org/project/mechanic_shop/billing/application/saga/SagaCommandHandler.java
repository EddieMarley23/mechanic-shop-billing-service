package org.project.mechanic_shop.billing.application.saga;

import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.billing.application.ports.ProcessedMessagePort;
import org.project.mechanic_shop.billing.application.ports.SagaEventPublisher;
import org.project.mechanic_shop.billing.application.services.BudgetService;
import org.project.mechanic_shop.billing.application.services.PaymentService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Executes the commands the Saga orchestrator sends to the Billing Service.
 *
 * <p>Everything a command does — the idempotency record, the state change and the reply written to
 * the outbox — happens in one local transaction. A redelivered command is detected and ignored.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaCommandHandler {

	private final ProcessedMessagePort processedMessages;
	private final BudgetService budgetService;
	private final PaymentService paymentService;
	private final SagaEventPublisher sagaEventPublisher;
	private final ObjectMapper objectMapper;

	@Transactional
	public void handle(SagaMessage message) {
		if (!processedMessages.registerIfNew(message.messageId(), message.type())) {
			log.info("Duplicated message {} ({}) ignored.", message.messageId(), message.type());
			return;
		}

		log.info("Handling command {} for Service Order {}", message.type(), message.serviceOrderId());

		switch (message.type()) {
			case SagaMessageTypes.BUDGET_GENERATE -> generateBudget(message);
			case SagaMessageTypes.BUDGET_CANCEL -> cancelBudget(message);
			case SagaMessageTypes.PAYMENT_CREATE -> paymentService.createForOrder(message.sagaId(), message.serviceOrderId());
			case SagaMessageTypes.PAYMENT_REFUND -> paymentService.refund(
				message.sagaId(),
				message.serviceOrderId(),
				reasonOf(message)
			);
			default -> log.warn("Unknown command type '{}' ignored.", message.type());
		}
	}

	private void generateBudget(SagaMessage message) {
		GenerateBudgetPayload payload = objectMapper.treeToValue(message.payload(), GenerateBudgetPayload.class);

		Optional<String> error = payload.validationError();
		if (error.isPresent()) {
			log.warn("Budget for Service Order {} refused: {}", message.serviceOrderId(), error.get());
			sagaEventPublisher.publish(
				message.sagaId(),
				message.serviceOrderId(),
				SagaMessageTypes.BUDGET_FAILED,
				Map.of("reason", error.get())
			);
			return;
		}

		budgetService.generate(message.sagaId(), message.serviceOrderId(), payload);
	}

	private void cancelBudget(SagaMessage message) {
		String reason = reasonOf(message);
		paymentService.cancelPending(message.serviceOrderId());
		budgetService.cancel(message.serviceOrderId(), reason);
	}

	private String reasonOf(SagaMessage message) {
		if (message.payload() == null || message.payload().isNull()) return null;
		return objectMapper.treeToValue(message.payload(), ReasonPayload.class).reason();
	}
}
