package org.project.mechanic_shop.billing.application.ports.payment;

import java.util.UUID;

/**
 * A payment status change reported by the gateway, already verified and normalized.
 *
 * @param paymentId our payment identifier, taken from the reference sent in {@link ChargeRequest}
 * @param providerPaymentId identifier of the payment at the gateway, needed later for refunds
 */
public record GatewayNotification(
	UUID paymentId,
	String providerPaymentId,
	GatewayPaymentStatusEnum status,
	String detail
) {}
