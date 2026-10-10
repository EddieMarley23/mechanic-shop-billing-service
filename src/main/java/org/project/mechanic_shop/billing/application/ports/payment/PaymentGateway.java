package org.project.mechanic_shop.billing.application.ports.payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.project.mechanic_shop.billing.domain.enums.PaymentProviderEnum;

/**
 * Port to the external payment provider.
 *
 * <p>The Billing Service only knows this contract. The adapter is chosen with
 * {@code billing.payment.provider}: {@code simulated} (default) or {@code mercadopago}.
 */
public interface PaymentGateway {
	PaymentProviderEnum provider();

	/**
	 * Creates a charge and returns where the customer pays it. Must be idempotent on
	 * {@link ChargeRequest#paymentId()}.
	 *
	 * @throws PaymentGatewayException when the gateway rejects the request or is unreachable
	 */
	ChargeResult createCharge(ChargeRequest request);

	/**
	 * Verifies the authenticity of a webhook call and resolves the payment it refers to.
	 *
	 * @param headers request headers, lower-cased names
	 * @param queryParams request query string parameters
	 * @param rawBody request body exactly as received (signatures are computed over it)
	 * @return empty when the notification is valid but not about a payment (must be acknowledged
	 *     and ignored)
	 * @throws PaymentGatewayException when the signature is invalid or the payment lookup fails
	 */
	Optional<GatewayNotification> parseNotification(
		Map<String, String> headers,
		Map<String, String> queryParams,
		String rawBody
	);

	/**
	 * Returns the full amount of a paid charge. Must be idempotent on {@code idempotencyKey}.
	 *
	 * @throws PaymentGatewayException when the gateway rejects the request or is unreachable
	 */
	void refund(String providerPaymentId, BigDecimal amount, String idempotencyKey);
}
