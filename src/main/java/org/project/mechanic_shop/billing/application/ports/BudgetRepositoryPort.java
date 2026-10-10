package org.project.mechanic_shop.billing.application.ports;

import java.util.Optional;
import java.util.UUID;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;

public interface BudgetRepositoryPort {
	Budget save(Budget budget);
	Optional<Budget> findById(UUID id);
	Optional<Budget> findByServiceOrderId(UUID serviceOrderId);
	Optional<Budget> findByApprovalToken(String approvalToken);
}
