package org.project.mechanic_shop.billing.domain.dto;

import java.math.BigDecimal;
import java.util.UUID;
import org.project.mechanic_shop.billing.domain.entities.budget.BudgetItem;
import org.project.mechanic_shop.billing.domain.enums.BudgetItemTypeEnum;

public record BudgetItemDto(
	BudgetItemTypeEnum type,
	UUID referenceId,
	String description,
	Integer quantity,
	BigDecimal unitPrice,
	BigDecimal totalPrice
) {
	public static BudgetItemDto from(BudgetItem item) {
		return new BudgetItemDto(
			item.getType(),
			item.getReferenceId(),
			item.getDescription(),
			item.getQuantity(),
			item.getUnitPrice(),
			item.getTotalPrice()
		);
	}
}
