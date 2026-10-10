package org.project.mechanic_shop.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.billing.application.saga.GenerateBudgetPayload;
import org.project.mechanic_shop.billing.application.saga.SagaMessage;
import org.project.mechanic_shop.billing.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.billing.domain.enums.BudgetStatusEnum;
import org.project.mechanic_shop.billing.support.BillingTestHarness;

class SagaCommandHandlerTest {

	private BillingTestHarness harness;

	@BeforeEach
	void setup() {
		harness = new BillingTestHarness();
	}

	@Test
	void shouldAnswerWithAFailureInsteadOfThrowingWhenTheBudgetRequestIsInvalid() {
		var invalid = new GenerateBudgetPayload("Cliente", "c@t.com", "ABC1D23", List.of(), new BigDecimal("10.00"), 1);

		harness.receive(SagaMessageTypes.BUDGET_GENERATE, invalid);

		assertThat(harness.budgetRepository.count()).isZero();
		assertThat(harness.lastEvent(SagaMessageTypes.BUDGET_FAILED).payload()).containsEntry(
			"reason",
			"Budget must have at least one item."
		);
	}

	@Test
	void shouldIgnoreARedeliveredCommand() {
		harness.receive(SagaMessageTypes.BUDGET_GENERATE, harness.budgetPayload());
		harness.budgetService.respond(harness.serviceOrderId, true, BillingTestHarness.CUSTOMER);
		SagaMessage createPayment = harness.command(SagaMessageTypes.PAYMENT_CREATE, java.util.Map.of());

		harness.commandHandler.handle(createPayment);
		harness.commandHandler.handle(createPayment);

		assertThat(harness.gateway.charges).hasSize(1);
		assertThat(harness.sagaEventTypes()).containsOnlyOnce(SagaMessageTypes.PAYMENT_CREATED);
	}

	@Test
	void shouldCancelTheBudgetEvenWithoutAReason() {
		harness.receive(SagaMessageTypes.BUDGET_GENERATE, harness.budgetPayload());

		harness.commandHandler.handle(
			new SagaMessage(
				java.util.UUID.randomUUID(),
				harness.sagaId,
				harness.serviceOrderId,
				SagaMessageTypes.BUDGET_CANCEL,
				harness.clock.instant(),
				null,
				null
			)
		);

		assertThat(harness.budget().getStatus()).isEqualTo(BudgetStatusEnum.CANCELED);
	}

	@Test
	void shouldIgnoreAnUnknownCommandType() {
		harness.receive("billing.something.new");

		assertThat(harness.sagaEvents).isEmpty();
		assertThat(harness.budgetRepository.count()).isZero();
	}
}
