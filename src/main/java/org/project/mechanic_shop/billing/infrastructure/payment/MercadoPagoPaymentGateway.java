package org.project.mechanic_shop.billing.infrastructure.payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.project.mechanic_shop.billing.application.ports.payment.ChargeRequest;
import org.project.mechanic_shop.billing.application.ports.payment.ChargeResult;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayNotification;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGateway;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGatewayException;
import org.project.mechanic_shop.billing.domain.enums.PaymentProviderEnum;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Mercado Pago adapter — <strong>prepared, not implemented yet</strong>.
 *
 * <p>Activated with {@code billing.payment.provider=mercadopago}. Every method currently fails with
 * a {@link PaymentGatewayException}; the rest of the service (payment lifecycle, webhook endpoint,
 * Saga replies, refunds) already goes through the {@link PaymentGateway} port, so implementing the
 * three methods below is all that is left. Nothing outside this class needs to change.
 *
 * <p>What each method has to do (Checkout Pro flow):
 *
 * <ul>
 *   <li>{@link #createCharge}: {@code POST {apiBaseUrl}/checkout/preferences} with the item
 *       (title, quantity 1, {@code unit_price} = amount), {@code payer.email},
 *       {@code external_reference} = {@code paymentId}, {@code notification_url} and the
 *       {@code X-Idempotency-Key} header = {@code paymentId}. Return the preference {@code id} as
 *       the provider reference and {@code init_point} as the checkout URL.
 *   <li>{@link #parseNotification}: validate the {@code x-signature} header (HMAC-SHA256, keyed
 *       with the webhook secret, over the manifest
 *       {@code id:{data.id};request-id:{x-request-id};ts:{ts};}), ignore notifications whose
 *       {@code type} is not {@code payment}, then {@code GET {apiBaseUrl}/v1/payments/{data.id}}
 *       and map {@code status} ({@code approved} → APPROVED; {@code rejected}/{@code cancelled} →
 *       REJECTED; anything else → PENDING), {@code external_reference} → our payment id and
 *       {@code id} → provider payment id.
 *   <li>{@link #refund}: {@code POST {apiBaseUrl}/v1/payments/{providerPaymentId}/refunds} with the
 *       {@code X-Idempotency-Key} header. An empty body refunds the full amount.
 * </ul>
 *
 * <p>All calls authenticate with {@code Authorization: Bearer {accessToken}}.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "billing.payment.provider", havingValue = "mercadopago")
@EnableConfigurationProperties(MercadoPagoProperties.class)
public class MercadoPagoPaymentGateway implements PaymentGateway {

	private static final String NOT_IMPLEMENTED_MSG =
		"Mercado Pago integration is not implemented yet. Set billing.payment.provider=simulated.";

	private final MercadoPagoProperties properties;

	@Override
	public PaymentProviderEnum provider() {
		return PaymentProviderEnum.MERCADO_PAGO;
	}

	@Override
	public ChargeResult createCharge(ChargeRequest request) {
		throw new PaymentGatewayException(NOT_IMPLEMENTED_MSG);
	}

	@Override
	public Optional<GatewayNotification> parseNotification(
		Map<String, String> headers,
		Map<String, String> queryParams,
		String rawBody
	) {
		throw new PaymentGatewayException(NOT_IMPLEMENTED_MSG);
	}

	@Override
	public void refund(String providerPaymentId, BigDecimal amount, String idempotencyKey) {
		throw new PaymentGatewayException(NOT_IMPLEMENTED_MSG);
	}

	/** Whether the credentials needed to call Mercado Pago are configured. */
	public boolean isConfigured() {
		return properties.accessToken() != null && !properties.accessToken().isBlank();
	}
}
