package org.project.mechanic_shop.billing.infrastructure.jpa.entities;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.project.mechanic_shop.billing.domain.enums.BudgetStatusEnum;

@Entity
@Table(name = "budgets")
@Getter
@Setter
@NoArgsConstructor
public class BudgetJpaEntity {

	@Id
	private UUID id;

	@Column(name = "service_order_id", nullable = false, unique = true, updatable = false)
	private UUID serviceOrderId;

	@Column(name = "saga_id", nullable = false, updatable = false)
	private UUID sagaId;

	@Column(name = "customer_name")
	private String customerName;

	@Column(name = "customer_email")
	private String customerEmail;

	@Column(name = "license_plate")
	private String licensePlate;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "budget_items", joinColumns = @JoinColumn(name = "budget_id"))
	@OrderColumn(name = "position")
	private List<BudgetItemEmbeddable> items = new ArrayList<>();

	@Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
	private BigDecimal totalAmount = BigDecimal.ZERO;

	@Column(name = "estimated_completion_days")
	private Integer estimatedCompletionDays;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private BudgetStatusEnum status = BudgetStatusEnum.SENT;

	@Column(name = "approval_token", unique = true)
	private String approvalToken;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "responded_at")
	private Instant respondedAt;
}
