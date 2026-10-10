package org.project.mechanic_shop.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.billing.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.project.mechanic_shop.billing.domain.enums.BudgetItemTypeEnum;
import org.project.mechanic_shop.billing.domain.enums.BudgetStatusEnum;
import org.project.mechanic_shop.billing.support.BillingTestHarness;
import org.springframework.security.access.AccessDeniedException;

/** Edge cases of the budget use cases. The main flows are specified in Gherkin. */
class BudgetServiceTest {

	private BillingTestHarness harness;

	@BeforeEach
	void setup() {
		harness = new BillingTestHarness();
	}

	private Budget generate() {
		return harness.budgetService.generate(harness.sagaId, harness.serviceOrderId, harness.budgetPayload());
	}

	@Test
	void shouldKeepACopyOfEverythingReceivedFromTheOrchestrator() {
		Budget budget = generate();

		assertThat(budget.getId()).isNotNull();
		assertThat(budget.getSagaId()).isEqualTo(harness.sagaId);
		assertThat(budget.getCustomerName()).isEqualTo("Cliente Teste");
		assertThat(budget.getLicensePlate()).isEqualTo("ABC1D23");
		assertThat(budget.getTotalAmount()).isEqualByComparingTo("420.00");
		assertThat(budget.getEstimatedCompletionDays()).isEqualTo(1);
		assertThat(budget.getCreatedAt()).isEqualTo(harness.clock.instant());
		assertThat(budget.getItems()).hasSize(2);
		assertThat(budget.getItems().get(0).getType()).isEqualTo(BudgetItemTypeEnum.LABOR);
		assertThat(budget.getItems().get(1).getDescription()).isEqualTo("Pastilha de freio");
		assertThat(budget.getItems().get(1).getQuantity()).isEqualTo(2);
	}

	@Test
	void shouldReportTheBudgetIdAndTotalToTheSaga() {
		Budget budget = generate();

		var event = harness.lastEvent(SagaMessageTypes.BUDGET_SENT);
		assertThat(event.payload()).containsEntry("budgetId", budget.getId().toString());
		assertThat(event.payload().get("totalAmount").toString()).isEqualTo("420.00");
	}

	@Test
	void shouldReturnTheExistingBudgetWhenGenerationIsRepeated() {
		Budget first = generate();
		Budget second = generate();

		assertThat(second.getId()).isEqualTo(first.getId());
		assertThat(harness.sagaEventTypes()).containsOnlyOnce(SagaMessageTypes.BUDGET_SENT);
	}

	@Test
	void shouldLetStaffRespondOnBehalfOfTheCustomer() {
		generate();

		Budget budget = harness.budgetService.respond(harness.serviceOrderId, true, BillingTestHarness.ADMIN);

		assertThat(budget.getStatus()).isEqualTo(BudgetStatusEnum.APPROVED);
		assertThat(budget.getRespondedAt()).isEqualTo(harness.clock.instant());
	}

	@Test
	void shouldNotAcceptAResponseTwice() {
		generate();
		harness.budgetService.respond(harness.serviceOrderId, true, BillingTestHarness.CUSTOMER);

		assertThatThrownBy(() -> harness.budgetService.respond(harness.serviceOrderId, false, BillingTestHarness.CUSTOMER))
			.isInstanceOf(IllegalStateException.class);
		assertThat(harness.sagaEventTypes()).doesNotContain(SagaMessageTypes.BUDGET_REJECTED);
	}

	@Test
	void shouldFailWhenRespondingToAnUnknownOrder() {
		assertThatThrownBy(() -> harness.budgetService.respond(UUID.randomUUID(), true, BillingTestHarness.ADMIN))
			.isInstanceOf(EntityNotFoundException.class);
	}

	@Test
	void shouldRejectByEmailToken() {
		String token = generate().getApprovalToken();

		Budget budget = harness.budgetService.respondByToken(token, false);

		assertThat(budget.getStatus()).isEqualTo(BudgetStatusEnum.REJECTED);
		assertThat(harness.sagaEventTypes()).contains(SagaMessageTypes.BUDGET_REJECTED);
	}

	@Test
	void shouldFailForAnUnknownEmailToken() {
		assertThatThrownBy(() -> harness.budgetService.respondByToken("nope", true)).isInstanceOf(
			EntityNotFoundException.class
		);
	}

	@Test
	void shouldLetOnlyTheOwnerOrStaffSeeTheBudget() {
		generate();

		assertThat(harness.budgetService.findByServiceOrderId(harness.serviceOrderId, BillingTestHarness.CUSTOMER)).isNotNull();
		assertThat(harness.budgetService.findByServiceOrderId(harness.serviceOrderId, BillingTestHarness.ADMIN)).isNotNull();
		assertThat(harness.budgetService.findByServiceOrderId(harness.serviceOrderId, null)).isNotNull();
		assertThatThrownBy(() ->
			harness.budgetService.findByServiceOrderId(harness.serviceOrderId, BillingTestHarness.OTHER_CUSTOMER)
		).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void shouldFailWhenLookingUpABudgetThatWasNotGenerated() {
		assertThatThrownBy(() -> harness.budgetService.findByServiceOrderId(UUID.randomUUID(), BillingTestHarness.ADMIN))
			.isInstanceOf(EntityNotFoundException.class);
	}

	@Test
	void shouldIgnoreTheCancellationOfABudgetThatDoesNotExist() {
		harness.budgetService.cancel(UUID.randomUUID(), "x");

		assertThat(harness.budgetRepository.count()).isZero();
	}
}
