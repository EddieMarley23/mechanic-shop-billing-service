package org.project.mechanic_shop.billing.infrastructure.jpa.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.project.mechanic_shop.billing.domain.enums.BudgetItemTypeEnum;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BudgetItemEmbeddable {

	@Enumerated(EnumType.STRING)
	@Column(name = "item_type", nullable = false, length = 10)
	private BudgetItemTypeEnum type;

	@Column(name = "reference_id")
	private UUID referenceId;

	@Column(nullable = false)
	private String description;

	@Column(nullable = false)
	private Integer quantity;

	@Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
	private BigDecimal unitPrice;

	@Column(name = "total_price", nullable = false, precision = 10, scale = 2)
	private BigDecimal totalPrice;
}
