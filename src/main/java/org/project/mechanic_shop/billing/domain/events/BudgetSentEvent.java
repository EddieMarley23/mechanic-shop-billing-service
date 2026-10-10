package org.project.mechanic_shop.billing.domain.events;

import org.project.mechanic_shop.billing.domain.entities.budget.Budget;

public record BudgetSentEvent(Budget budget, String approvalToken) {}
