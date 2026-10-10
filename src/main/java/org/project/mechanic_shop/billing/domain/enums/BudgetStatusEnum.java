package org.project.mechanic_shop.billing.domain.enums;

import lombok.Getter;

@Getter
public enum BudgetStatusEnum {
	SENT("Sent", "Budget sent to the customer for approval."),
	APPROVED("Approved", "Customer approved the budget."),
	REJECTED("Rejected", "Customer rejected the budget."),
	CANCELED("Canceled", "Budget voided by a Saga compensation.");

	private final String label;
	private final String description;

	BudgetStatusEnum(String label, String description) {
		this.label = label;
		this.description = description;
	}
}
