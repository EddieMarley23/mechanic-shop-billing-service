package org.project.mechanic_shop.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.project.mechanic_shop.billing.domain.enums.BudgetStatusEnum;

class BudgetTest {

	private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");

	private Budget sentBudget() {
		Budget budget = new Budget();
		budget.setCustomerEmail("cliente@teste.com");
		budget.setApprovalToken("token");
		return budget;
	}

	@Test
	void shouldApproveAndBurnTheToken() {
		Budget budget = sentBudget();

		budget.approve(NOW);

		assertThat(budget.getStatus()).isEqualTo(BudgetStatusEnum.APPROVED);
		assertThat(budget.getRespondedAt()).isEqualTo(NOW);
		assertThat(budget.getApprovalToken()).isNull();
	}

	@Test
	void shouldRejectAndBurnTheToken() {
		Budget budget = sentBudget();

		budget.reject(NOW);

		assertThat(budget.getStatus()).isEqualTo(BudgetStatusEnum.REJECTED);
		assertThat(budget.getApprovalToken()).isNull();
	}

	@Test
	void shouldNotAcceptASecondResponse() {
		Budget budget = sentBudget();
		budget.approve(NOW);

		assertThatThrownBy(() -> budget.reject(NOW))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("APPROVED");
		assertThatThrownBy(() -> budget.approve(NOW)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldCancelOnlyOnce() {
		Budget budget = sentBudget();
		budget.approve(NOW);

		assertThat(budget.cancel()).isTrue();
		assertThat(budget.cancel()).isFalse();
		assertThat(budget.getStatus()).isEqualTo(BudgetStatusEnum.CANCELED);
	}

	@Test
	void shouldKeepARejectedBudgetRejectedWhenCanceled() {
		Budget budget = sentBudget();
		budget.reject(NOW);

		assertThat(budget.cancel()).isFalse();
		assertThat(budget.getStatus()).isEqualTo(BudgetStatusEnum.REJECTED);
	}

	@Test
	void shouldRecognizeItsOwnerIgnoringCase() {
		Budget budget = sentBudget();

		assertThat(budget.belongsTo("CLIENTE@teste.com")).isTrue();
		assertThat(budget.belongsTo("outro@teste.com")).isFalse();
		assertThat(budget.belongsTo(null)).isFalse();
	}

	@Test
	void shouldDescribeEveryStatus() {
		for (BudgetStatusEnum status : BudgetStatusEnum.values()) {
			assertThat(status.getLabel()).isNotBlank();
			assertThat(status.getDescription()).isNotBlank();
		}
	}
}
