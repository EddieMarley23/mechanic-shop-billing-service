package org.project.mechanic_shop.billing.application.saga;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.project.mechanic_shop.billing.domain.enums.BudgetItemTypeEnum;

/**
 * Payload of {@link SagaMessageTypes#BUDGET_GENERATE}. The orchestrator sends the items already
 * priced, so this service never has to query the catalog or the stock of the OS Service.
 */
public record GenerateBudgetPayload(
	String customerName,
	String customerEmail,
	String licensePlate,
	List<Item> items,
	BigDecimal totalAmount,
	Integer estimatedCompletionDays
) {
	public record Item(
		BudgetItemTypeEnum type,
		UUID referenceId,
		String description,
		Integer quantity,
		BigDecimal unitPrice,
		BigDecimal totalPrice
	) {}

	/** @return the reason this payload cannot become a budget, or empty when it is valid */
	public Optional<String> validationError() {
		if (items == null || items.isEmpty()) return Optional.of("Budget must have at least one item.");
		if (totalAmount == null || totalAmount.signum() <= 0) {
			return Optional.of("Budget total must be greater than zero.");
		}

		BigDecimal sum = BigDecimal.ZERO;
		for (Item item : items) {
			if (item.quantity() == null || item.quantity() < 1 || item.unitPrice() == null || item.totalPrice() == null) {
				return Optional.of("Budget item '" + item.description() + "' has no quantity or price.");
			}
			sum = sum.add(item.totalPrice());
		}

		if (sum.compareTo(totalAmount) != 0) {
			return Optional.of("Budget total " + totalAmount + " does not match the sum of its items " + sum + ".");
		}
		return Optional.empty();
	}
}
