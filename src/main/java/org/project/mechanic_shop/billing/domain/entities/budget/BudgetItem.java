package org.project.mechanic_shop.billing.domain.entities.budget;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.project.mechanic_shop.billing.domain.enums.BudgetItemTypeEnum;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BudgetItem {
	private BudgetItemTypeEnum type;
	/** External ID of the catalog service or stock item in the OS Service. Kept only as a reference. */
	private UUID referenceId;
	private String description;
	private Integer quantity;
	private BigDecimal unitPrice;
	private BigDecimal totalPrice;
}
