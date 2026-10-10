package org.project.mechanic_shop.billing.domain.entities.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.project.mechanic_shop.billing.domain.enums.BudgetStatusEnum;

@Getter
@Setter
@NoArgsConstructor
public class Budget {
	private UUID id;
	private UUID serviceOrderId;
	private UUID sagaId;
	private String customerName;
	private String customerEmail;
	private String licensePlate;
	private List<BudgetItem> items = new ArrayList<>();
	private BigDecimal totalAmount = BigDecimal.ZERO;
	private Integer estimatedCompletionDays;
	private BudgetStatusEnum status = BudgetStatusEnum.SENT;
	private String approvalToken;
	private Instant createdAt;
	private Instant respondedAt;

	public void approve(Instant now) {
		assertAwaitingResponse();
		this.status = BudgetStatusEnum.APPROVED;
		this.respondedAt = now;
		this.approvalToken = null;
	}

	public void reject(Instant now) {
		assertAwaitingResponse();
		this.status = BudgetStatusEnum.REJECTED;
		this.respondedAt = now;
		this.approvalToken = null;
	}

	/**
	 * Voids the budget as part of a Saga compensation. Safe to call more than once.
	 *
	 * @return {@code true} when the status actually changed
	 */
	public boolean cancel() {
		if (this.status == BudgetStatusEnum.CANCELED || this.status == BudgetStatusEnum.REJECTED) return false;
		this.status = BudgetStatusEnum.CANCELED;
		this.approvalToken = null;
		return true;
	}

	public boolean belongsTo(String email) {
		return email != null && email.equalsIgnoreCase(this.customerEmail);
	}

	private void assertAwaitingResponse() {
		if (this.status != BudgetStatusEnum.SENT) {
			throw new IllegalStateException("Budget is no longer awaiting a response. Current status: " + this.status);
		}
	}
}
