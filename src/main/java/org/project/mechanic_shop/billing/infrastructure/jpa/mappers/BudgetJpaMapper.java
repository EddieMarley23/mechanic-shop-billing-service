package org.project.mechanic_shop.billing.infrastructure.jpa.mappers;

import java.util.ArrayList;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.project.mechanic_shop.billing.domain.entities.budget.BudgetItem;
import org.project.mechanic_shop.billing.infrastructure.jpa.entities.BudgetItemEmbeddable;
import org.project.mechanic_shop.billing.infrastructure.jpa.entities.BudgetJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class BudgetJpaMapper {

	public Budget toDomain(BudgetJpaEntity jpa) {
		if (jpa == null) return null;

		Budget budget = new Budget();
		budget.setId(jpa.getId());
		budget.setServiceOrderId(jpa.getServiceOrderId());
		budget.setSagaId(jpa.getSagaId());
		budget.setCustomerName(jpa.getCustomerName());
		budget.setCustomerEmail(jpa.getCustomerEmail());
		budget.setLicensePlate(jpa.getLicensePlate());
		budget.setTotalAmount(jpa.getTotalAmount());
		budget.setEstimatedCompletionDays(jpa.getEstimatedCompletionDays());
		budget.setStatus(jpa.getStatus());
		budget.setApprovalToken(jpa.getApprovalToken());
		budget.setCreatedAt(jpa.getCreatedAt());
		budget.setRespondedAt(jpa.getRespondedAt());
		budget.setItems(
			new ArrayList<>(
				jpa
					.getItems()
					.stream()
					.map(i ->
						new BudgetItem(
							i.getType(),
							i.getReferenceId(),
							i.getDescription(),
							i.getQuantity(),
							i.getUnitPrice(),
							i.getTotalPrice()
						)
					)
					.toList()
			)
		);
		return budget;
	}

	public BudgetJpaEntity toJpa(Budget budget) {
		if (budget == null) return null;

		BudgetJpaEntity jpa = new BudgetJpaEntity();
		jpa.setId(budget.getId());
		jpa.setServiceOrderId(budget.getServiceOrderId());
		jpa.setSagaId(budget.getSagaId());
		jpa.setCustomerName(budget.getCustomerName());
		jpa.setCustomerEmail(budget.getCustomerEmail());
		jpa.setLicensePlate(budget.getLicensePlate());
		jpa.setTotalAmount(budget.getTotalAmount());
		jpa.setEstimatedCompletionDays(budget.getEstimatedCompletionDays());
		jpa.setStatus(budget.getStatus());
		jpa.setApprovalToken(budget.getApprovalToken());
		jpa.setCreatedAt(budget.getCreatedAt());
		jpa.setRespondedAt(budget.getRespondedAt());
		jpa.setItems(
			new ArrayList<>(
				budget
					.getItems()
					.stream()
					.map(i ->
						new BudgetItemEmbeddable(
							i.getType(),
							i.getReferenceId(),
							i.getDescription(),
							i.getQuantity(),
							i.getUnitPrice(),
							i.getTotalPrice()
						)
					)
					.toList()
			)
		);
		return jpa;
	}
}
