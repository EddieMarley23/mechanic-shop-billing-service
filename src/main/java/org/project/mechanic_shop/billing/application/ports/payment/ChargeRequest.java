package org.project.mechanic_shop.billing.application.ports.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param paymentId our payment identifier. Gateways must echo it back in their notifications
 *     (Mercado Pago: {@code external_reference}) and may use it as the idempotency key
 */
public record ChargeRequest(
	UUID paymentId,
	UUID serviceOrderId,
	BigDecimal amount,
	String description,
	String payerName,
	String payerEmail
) {}
