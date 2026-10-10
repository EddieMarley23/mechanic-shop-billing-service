package org.project.mechanic_shop.billing.infrastructure.payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.billing.application.ports.payment.ChargeRequest;
import org.project.mechanic_shop.billing.application.ports.payment.ChargeResult;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayNotification;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGateway;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGatewayException;
import org.project.mechanic_shop.billing.domain.enums.PaymentProviderEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in gateway used until the Mercado Pago adapter is implemented, and in local development.
 *
 * <p>It accepts every charge and never calls back. The outcome of a payment is decided by a person
 * through {@code POST /api/billing/payments/{serviceOrderId}/simulate}, which plays the role of the
 * gateway's webhook.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "billing.payment.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedPaymentGateway implements PaymentGateway {

	@Value("${app.base-url}")
	private String baseUrl;

	@Override
	public PaymentProviderEnum provider() {
		return PaymentProviderEnum.SIMULATED;
	}

	@Override
	public ChargeResult createCharge(ChargeRequest request) {
		if (request.amount() == null || request.amount().signum() <= 0) {
			throw new PaymentGatewayException("Charge amount must be greater than zero.");
		}

		log.info("[SIMULATED GATEWAY] Charge of R$ {} created for payment {}", request.amount(), request.paymentId());

		return new ChargeResult(
			"SIM-" + request.paymentId(),
			baseUrl + "/api/billing/payments/" + request.serviceOrderId() + "/simulate"
		);
	}

	@Override
	public Optional<GatewayNotification> parseNotification(
		Map<String, String> headers,
		Map<String, String> queryParams,
		String rawBody
	) {
		throw new PaymentGatewayException(
			"The simulated gateway sends no webhooks. Use POST /api/billing/payments/{serviceOrderId}/simulate."
		);
	}

	@Override
	public void refund(String providerPaymentId, BigDecimal amount, String idempotencyKey) {
		log.info("[SIMULATED GATEWAY] Refund of R$ {} issued for payment {}", amount, providerPaymentId);
	}
}
