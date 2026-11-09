package org.project.mechanic_shop.billing.application.ports.payment;

/**
 * The webhook call could not be proven to come from the payment gateway (missing, malformed or
 * wrong signature). Answered with HTTP 401, as the gateway recommends.
 */
public class InvalidWebhookSignatureException extends PaymentGatewayException {

	public InvalidWebhookSignatureException(String message) {
		super(message);
	}
}
