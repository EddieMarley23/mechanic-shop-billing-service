package org.project.mechanic_shop.billing.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials and URLs of the Mercado Pago integration. Values come from the environment
 * ({@code MERCADOPAGO_*}); see {@code application.yaml}.
 *
 * @param accessToken private access token of the application (test or production)
 * @param webhookSecret secret used to validate the {@code x-signature} header of webhooks
 * @param apiBaseUrl Mercado Pago REST API base URL
 * @param notificationUrl public URL of {@code POST /api/billing/webhooks/mercadopago}
 */
@ConfigurationProperties(prefix = "billing.payment.mercadopago")
public record MercadoPagoProperties(
	String accessToken,
	String webhookSecret,
	String apiBaseUrl,
	String notificationUrl
) {}
