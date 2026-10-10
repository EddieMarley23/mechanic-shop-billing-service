package org.project.mechanic_shop.billing.infrastructure.jpa.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.project.mechanic_shop.billing.domain.enums.PaymentProviderEnum;
import org.project.mechanic_shop.billing.domain.enums.PaymentStatusEnum;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class PaymentJpaEntity {

	@Id
	private UUID id;

	@Column(name = "budget_id", nullable = false, updatable = false)
	private UUID budgetId;

	@Column(name = "service_order_id", nullable = false, unique = true, updatable = false)
	private UUID serviceOrderId;

	@Column(name = "saga_id", nullable = false, updatable = false)
	private UUID sagaId;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PaymentStatusEnum status = PaymentStatusEnum.PENDING;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PaymentProviderEnum provider;

	@Column(name = "provider_reference")
	private String providerReference;

	@Column(name = "provider_payment_id")
	private String providerPaymentId;

	@Column(name = "checkout_url", length = 1000)
	private String checkoutUrl;

	@Column(name = "failure_reason", length = 1000)
	private String failureReason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
