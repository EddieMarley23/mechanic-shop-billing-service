package org.project.mechanic_shop.billing.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.project.mechanic_shop.billing.domain.entities.payment.Payment;
import org.project.mechanic_shop.billing.domain.enums.PaymentProviderEnum;
import org.project.mechanic_shop.billing.domain.enums.PaymentStatusEnum;

@Schema(description = "Payment of a service order budget")
public record PaymentDto(
	UUID id,
	UUID serviceOrderId,
	UUID budgetId,
	BigDecimal amount,
	PaymentStatusEnum status,
	PaymentProviderEnum provider,
	@Schema(description = "URL where the customer completes the payment") String checkoutUrl,
	String failureReason,
	Instant createdAt,
	Instant updatedAt
) {
	public static PaymentDto from(Payment payment) {
		return new PaymentDto(
			payment.getId(),
			payment.getServiceOrderId(),
			payment.getBudgetId(),
			payment.getAmount(),
			payment.getStatus(),
			payment.getProvider(),
			payment.getCheckoutUrl(),
			payment.getFailureReason(),
			payment.getCreatedAt(),
			payment.getUpdatedAt()
		);
	}
}
