package org.project.mechanic_shop.billing.application.services;

import java.util.UUID;
import org.project.mechanic_shop.billing.application.saga.GenerateBudgetPayload;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;

public interface BudgetService {
	Budget generate(UUID sagaId, UUID serviceOrderId, GenerateBudgetPayload payload);
	Budget respond(UUID serviceOrderId, boolean approved, Requester requester);
	Budget respondByToken(String token, boolean approved);
	void cancel(UUID serviceOrderId, String reason);
	Budget findByServiceOrderId(UUID serviceOrderId, Requester requester);
}
