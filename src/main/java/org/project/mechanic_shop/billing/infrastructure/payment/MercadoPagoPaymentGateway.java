package org.project.mechanic_shop.billing.infrastructure.payment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.billing.application.ports.payment.ChargeRequest;
import org.project.mechanic_shop.billing.application.ports.payment.ChargeResult;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayNotification;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayPaymentStatusEnum;
import org.project.mechanic_shop.billing.application.ports.payment.InvalidWebhookSignatureException;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGateway;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGatewayException;
import org.project.mechanic_shop.billing.domain.enums.PaymentProviderEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mercado Pago adapter (Checkout Pro). Activated with {@code billing.payment.provider=mercadopago}.
 *
 * <ul>
 *   <li>{@link #createCharge}: creates a preference ({@code POST /checkout/preferences}) whose
 *       {@code external_reference} is our payment id, and returns its {@code init_point} as the
 *       checkout URL sent to the customer.
 *   <li>{@link #parseNotification}: validates the {@code x-signature} header of the webhook, then
 *       reads the payment from {@code GET /v1/payments/{id}}. The webhook body is never trusted for
 *       the status: the API is the source of truth.
 *   <li>{@link #refund}: full refund ({@code POST /v1/payments/{id}/refunds} with an empty body and
 *       the mandatory {@code X-Idempotency-Key}).
 * </ul>
 *
 * <p>Signature: {@code x-signature} is {@code ts=<unix seconds>,v1=<hex>}, where {@code v1} is the
 * HMAC-SHA256 (lower-case hex) of the manifest {@code id:<data.id>;request-id:<x-request-id>;ts:<ts>;}
 * keyed with the webhook secret. {@code data.id} comes from the query string as received; the
 * {@code id} and {@code request-id} pairs are omitted when absent. This mirrors the official SDK
 * ({@code com.mercadopago.webhook.WebhookSignatureValidator}).
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "billing.payment.provider", havingValue = "mercadopago")
@EnableConfigurationProperties(MercadoPagoProperties.class)
public class MercadoPagoPaymentGateway implements PaymentGateway {

	static final String SIGNATURE_HEADER = "x-signature";
	static final String REQUEST_ID_HEADER = "x-request-id";
	static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";
	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final String PAYMENT_TOPIC = "payment";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final MercadoPagoProperties properties;
	private final RestClient restClient;

	@Autowired
	public MercadoPagoPaymentGateway(MercadoPagoProperties properties) {
		this(properties, RestClient.builder().requestFactory(requestFactory()));
	}

	/** Used by tests to bind a mock server to the builder. */
	MercadoPagoPaymentGateway(MercadoPagoProperties properties, RestClient.Builder builder) {
		this.properties = properties;
		String baseUrl = properties.apiBaseUrl() != null ? properties.apiBaseUrl() : "https://api.mercadopago.com";
		this.restClient = builder.baseUrl(baseUrl).build();
	}

	/** The webhook must be acknowledged within 22 seconds, so gateway calls cannot hang. */
	private static SimpleClientHttpRequestFactory requestFactory() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(10));
		return factory;
	}

	@Override
	public PaymentProviderEnum provider() {
		return PaymentProviderEnum.MERCADO_PAGO;
	}

	@Override
	public ChargeResult createCharge(ChargeRequest request) {
		requireAccessToken();
		if (request.amount() == null || request.amount().signum() <= 0) {
			throw new PaymentGatewayException("Charge amount must be greater than zero.");
		}

		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", String.valueOf(request.serviceOrderId()));
		item.put("title", request.description());
		item.put("quantity", 1);
		item.put("currency_id", "BRL");
		item.put("unit_price", request.amount());

		Map<String, Object> payer = new LinkedHashMap<>();
		payer.put("name", request.payerName());
		payer.put("email", request.payerEmail());

		Map<String, Object> preference = new LinkedHashMap<>();
		preference.put("items", List.of(item));
		preference.put("payer", payer);
		preference.put("external_reference", request.paymentId().toString());
		if (hasText(properties.notificationUrl())) {
			preference.put("notification_url", properties.notificationUrl());
		}

		JsonNode response = call("createCharge", () ->
			restClient
				.post()
				.uri("/checkout/preferences")
				.headers(this::authenticate)
				.header(IDEMPOTENCY_HEADER, request.paymentId().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.body(JSON.writeValueAsString(preference))
				.retrieve()
				.body(String.class)
		);

		String preferenceId = text(response, "id");
		String checkoutUrl = Optional.ofNullable(text(response, "init_point")).orElse(text(response, "sandbox_init_point"));
		if (preferenceId == null || checkoutUrl == null) {
			throw new PaymentGatewayException("Mercado Pago returned a preference without id or checkout URL.");
		}

		log.info("[MERCADO PAGO] Preference {} created for payment {}", preferenceId, request.paymentId());
		return new ChargeResult(preferenceId, checkoutUrl);
	}

	@Override
	public Optional<GatewayNotification> parseNotification(
		Map<String, String> headers,
		Map<String, String> queryParams,
		String rawBody
	) {
		// Legacy IPN calls (?topic=...&id=...) are unsigned and duplicate the webhook: acknowledge and
		// ignore them, so Mercado Pago does not keep retrying.
		if (queryParams.containsKey("topic")) {
			log.info("[MERCADO PAGO] IPN notification ({}) ignored; webhooks are used instead.", queryParams.get("topic"));
			return Optional.empty();
		}

		String queryDataId = trimToNull(queryParams.get("data.id"));
		verifySignature(headers.get(SIGNATURE_HEADER), headers.get(REQUEST_ID_HEADER), queryDataId);

		JsonNode body = parseJson(rawBody);
		String type = Optional.ofNullable(trimToNull(queryParams.get("type"))).orElse(text(body, "type"));
		if (!PAYMENT_TOPIC.equals(type)) {
			log.info("[MERCADO PAGO] Notification of type '{}' ignored.", type);
			return Optional.empty();
		}

		String providerPaymentId = Optional.ofNullable(queryDataId).orElse(text(body.path("data"), "id"));
		if (providerPaymentId == null) {
			throw new PaymentGatewayException("Payment notification without data.id.");
		}

		requireAccessToken();
		JsonNode payment = call("getPayment", () ->
			restClient.get().uri("/v1/payments/{id}", providerPaymentId).headers(this::authenticate).retrieve().body(String.class)
		);

		UUID paymentId = parseUuid(text(payment, "external_reference"));
		if (paymentId == null) {
			log.warn("[MERCADO PAGO] Payment {} has no reference to a Billing payment. Ignored.", providerPaymentId);
			return Optional.empty();
		}

		String status = text(payment, "status");
		String statusDetail = text(payment, "status_detail");
		return Optional.of(
			new GatewayNotification(
				paymentId,
				Optional.ofNullable(text(payment, "id")).orElse(providerPaymentId),
				mapStatus(status),
				statusDetail != null ? "Mercado Pago: " + status + " (" + statusDetail + ")" : null
			)
		);
	}

	@Override
	public void refund(String providerPaymentId, BigDecimal amount, String idempotencyKey) {
		requireAccessToken();
		if (!hasText(providerPaymentId)) {
			throw new PaymentGatewayException("Cannot refund: the payment has no Mercado Pago id.");
		}

		// An empty body refunds the full amount.
		call("refund", () ->
			restClient
				.post()
				.uri("/v1/payments/{id}/refunds", providerPaymentId)
				.headers(this::authenticate)
				.header(IDEMPOTENCY_HEADER, idempotencyKey)
				.retrieve()
				.body(String.class)
		);
		log.info("[MERCADO PAGO] Refund of R$ {} requested for payment {}", amount, providerPaymentId);
	}

	/** Whether the credentials needed to call Mercado Pago are configured. */
	public boolean isConfigured() {
		return hasText(properties.accessToken());
	}

	// ---------------------------------------------------------------------------------------------

	static GatewayPaymentStatusEnum mapStatus(String status) {
		if (status == null) return GatewayPaymentStatusEnum.PENDING;
		return switch (status.toLowerCase(Locale.ROOT)) {
			case "approved" -> GatewayPaymentStatusEnum.APPROVED;
			case "rejected", "cancelled", "canceled" -> GatewayPaymentStatusEnum.REJECTED;
			// pending, in_process, authorized, in_mediation; refunded and charged_back only happen
			// after the payment was settled here, so they change nothing.
			default -> GatewayPaymentStatusEnum.PENDING;
		};
	}

	/** Manifest exactly as the official SDK builds it. */
	static String manifest(String dataId, String requestId, String ts) {
		StringBuilder sb = new StringBuilder();
		if (dataId != null) sb.append("id:").append(dataId).append(';');
		if (requestId != null) sb.append("request-id:").append(requestId).append(';');
		sb.append("ts:").append(ts).append(';');
		return sb.toString();
	}

	static String hmacSha256Hex(String secret, String message) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException("HMAC-SHA256 is not available.", e);
		}
	}

	private void verifySignature(String signatureHeader, String requestId, String dataId) {
		if (!hasText(properties.webhookSecret())) {
			throw new PaymentGatewayException("Mercado Pago webhook secret is not configured.");
		}
		if (!hasText(signatureHeader)) {
			throw new InvalidWebhookSignatureException("Missing x-signature header.");
		}

		String ts = null;
		String v1 = null;
		for (String part : signatureHeader.split(",")) {
			String[] kv = part.split("=", 2);
			if (kv.length != 2) continue;
			String key = kv[0].trim().toLowerCase(Locale.ROOT);
			if (key.equals("ts")) ts = kv[1].trim();
			if (key.equals("v1")) v1 = kv[1].trim();
		}
		if (ts == null || !ts.chars().allMatch(Character::isDigit) || v1 == null) {
			throw new InvalidWebhookSignatureException("Malformed x-signature header.");
		}

		String expected = hmacSha256Hex(properties.webhookSecret(), manifest(dataId, trimToNull(requestId), ts));
		boolean valid = MessageDigest.isEqual(
			expected.getBytes(StandardCharsets.UTF_8),
			v1.getBytes(StandardCharsets.UTF_8)
		);
		if (!valid) {
			log.warn("[MERCADO PAGO] Invalid webhook signature (x-request-id: {}).", requestId);
			throw new InvalidWebhookSignatureException("Invalid x-signature.");
		}
	}

	private void authenticate(HttpHeaders headers) {
		headers.setBearerAuth(properties.accessToken());
	}

	private void requireAccessToken() {
		if (!isConfigured()) {
			throw new PaymentGatewayException("Mercado Pago access token is not configured (MERCADOPAGO_ACCESS_TOKEN).");
		}
	}

	private interface Call {
		String execute();
	}

	private static JsonNode call(String operation, Call call) {
		try {
			return parseJson(call.execute());
		} catch (RestClientResponseException e) {
			throw new PaymentGatewayException(
				"Mercado Pago " + operation + " failed with HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(),
				e
			);
		} catch (RestClientException e) {
			throw new PaymentGatewayException("Mercado Pago " + operation + " failed: " + e.getMessage(), e);
		}
	}

	private static JsonNode parseJson(String raw) {
		if (raw == null || raw.isBlank()) return JSON.createObjectNode();
		try {
			return JSON.readTree(raw);
		} catch (JacksonException e) {
			throw new PaymentGatewayException("Unreadable JSON from Mercado Pago.", e);
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || value.isNull()) return null;
		String text = value.isValueNode() ? value.asString() : value.toString();
		return trimToNull(text);
	}

	private static UUID parseUuid(String value) {
		if (value == null) return null;
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
