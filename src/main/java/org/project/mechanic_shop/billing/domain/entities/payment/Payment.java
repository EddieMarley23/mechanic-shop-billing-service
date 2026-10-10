package org.project.mechanic_shop.billing.domain.entities.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.project.mechanic_shop.billing.domain.enums.PaymentProviderEnum;
import org.project.mechanic_shop.billing.domain.enums.PaymentStatusEnum;

@Getter
@Setter
@NoArgsConstructor
public class Payment {
	private UUID id;
	private UUID budgetId;
	private UUID serviceOrderId;
	private UUID sagaId;
	private BigDecimal amount;
	private PaymentStatusEnum status = PaymentStatusEnum.PENDING;
	private PaymentProviderEnum provider;
	/** Identifier of the charge at the gateway (e.g. the Mercado Pago preference id). */
	private String providerReference;
	/** Identifier of the actual payment at the gateway. Only known after the customer pays. */
	private String providerPaymentId;
	private String checkoutUrl;
	private String failureReason;
	private Instant createdAt;
	private Instant updatedAt;

	public void confirm(String providerPaymentId, Instant now) {
		assertPending();
		this.status = PaymentStatusEnum.CONFIRMED;
		this.providerPaymentId = providerPaymentId;
		this.updatedAt = now;
	}

	public void fail(String reason, Instant now) {
		assertPending();
		this.status = PaymentStatusEnum.FAILED;
		this.failureReason = reason;
		this.updatedAt = now;
	}

	public void refund(Instant now) {
		if (this.status != PaymentStatusEnum.CONFIRMED && this.status != PaymentStatusEnum.CANCELED) {
			throw new IllegalStateException("Only paid charges can be refunded. Current status: " + this.status);
		}
		this.status = PaymentStatusEnum.REFUNDED;
		this.updatedAt = now;
	}

	public void cancel(Instant now) {
		assertPending();
		this.status = PaymentStatusEnum.CANCELED;
		this.updatedAt = now;
	}

	public boolean isPending() {
		return this.status == PaymentStatusEnum.PENDING;
	}

	private void assertPending() {
		if (this.status != PaymentStatusEnum.PENDING) {
			throw new IllegalStateException("Payment is no longer pending. Current status: " + this.status);
		}
	}
}
