package org.project.mechanic_shop.billing.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.project.mechanic_shop.billing.domain.enums.BudgetStatusEnum;

@Schema(description = "Budget generated for a service order")
public record BudgetDto(
	UUID id,
	@Schema(description = "External ID of the service order in the OS Service") UUID serviceOrderId,
	String customerName,
	String licensePlate,
	List<BudgetItemDto> items,
	BigDecimal totalAmount,
	Integer estimatedCompletionDays,
	BudgetStatusEnum status,
	Instant createdAt,
	Instant respondedAt
) {
	public static BudgetDto from(Budget budget) {
		return new BudgetDto(
			budget.getId(),
			budget.getServiceOrderId(),
			budget.getCustomerName(),
			budget.getLicensePlate(),
			budget.getItems().stream().map(BudgetItemDto::from).toList(),
			budget.getTotalAmount(),
			budget.getEstimatedCompletionDays(),
			budget.getStatus(),
			budget.getCreatedAt(),
			budget.getRespondedAt()
		);
	}
}
