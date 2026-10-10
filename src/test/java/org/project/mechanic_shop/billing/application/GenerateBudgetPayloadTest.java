package org.project.mechanic_shop.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.billing.application.saga.GenerateBudgetPayload;
import org.project.mechanic_shop.billing.domain.enums.BudgetItemTypeEnum;

class GenerateBudgetPayloadTest {

	private GenerateBudgetPayload.Item item(Integer quantity, String unitPrice, String totalPrice) {
		return new GenerateBudgetPayload.Item(
			BudgetItemTypeEnum.LABOR,
			UUID.randomUUID(),
			"Troca de pastilhas",
			quantity,
			unitPrice != null ? new BigDecimal(unitPrice) : null,
			totalPrice != null ? new BigDecimal(totalPrice) : null
		);
	}

	private GenerateBudgetPayload payload(List<GenerateBudgetPayload.Item> items, String total) {
		return new GenerateBudgetPayload("Cliente", "c@t.com", "ABC1D23", items, total != null ? new BigDecimal(total) : null, 1);
	}

	@Test
	void shouldAcceptAPayloadWhoseTotalMatchesItsItems() {
		var payload = payload(List.of(item(1, "180.00", "180.00"), item(2, "120.00", "240.00")), "420.0");

		assertThat(payload.validationError()).isEmpty();
	}

	@Test
	void shouldRefuseAPayloadWithoutItems() {
		assertThat(payload(List.of(), "10.00").validationError()).hasValue("Budget must have at least one item.");
		assertThat(payload(null, "10.00").validationError()).isPresent();
	}

	@Test
	void shouldRefuseANonPositiveTotal() {
		var items = List.of(item(1, "0.00", "0.00"));

		assertThat(payload(items, "0.00").validationError()).hasValue("Budget total must be greater than zero.");
		assertThat(payload(items, null).validationError()).isPresent();
	}

	@Test
	void shouldRefuseAnItemWithoutQuantityOrPrice() {
		assertThat(payload(List.of(item(0, "10.00", "10.00")), "10.00").validationError()).isPresent();
		assertThat(payload(List.of(item(null, "10.00", "10.00")), "10.00").validationError()).isPresent();
		assertThat(payload(List.of(item(1, null, "10.00")), "10.00").validationError()).isPresent();
		assertThat(payload(List.of(item(1, "10.00", null)), "10.00").validationError()).isPresent();
	}

	@Test
	void shouldRefuseATotalThatDoesNotMatchTheItems() {
		var payload = payload(List.of(item(1, "180.00", "180.00")), "999.00");

		assertThat(payload.validationError()).get().asString().contains("does not match");
	}
}
