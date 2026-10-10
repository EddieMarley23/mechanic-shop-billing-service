package org.project.mechanic_shop.billing.domain.enums;

import lombok.Getter;

@Getter
public enum PaymentStatusEnum {
	PENDING("Pending", "Charge created. Awaiting the customer's payment."),
	CONFIRMED("Confirmed", "Payment approved by the gateway."),
	FAILED("Failed", "Payment rejected by the gateway or charge could not be created."),
	REFUNDED("Refunded", "Payment returned to the customer by a Saga compensation."),
	CANCELED("Canceled", "Charge voided before being paid.");

	private final String label;
	private final String description;

	PaymentStatusEnum(String label, String description) {
		this.label = label;
		this.description = description;
	}
}
