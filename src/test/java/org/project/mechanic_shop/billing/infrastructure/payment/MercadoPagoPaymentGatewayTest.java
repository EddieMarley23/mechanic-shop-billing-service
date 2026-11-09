package org.project.mechanic_shop.billing.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.project.mechanic_shop.billing.application.ports.payment.ChargeRequest;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayNotification;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayPaymentStatusEnum;
import org.project.mechanic_shop.billing.application.ports.payment.InvalidWebhookSignatureException;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGatewayException;
import org.project.mechanic_shop.billing.domain.enums.PaymentProviderEnum;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MercadoPagoPaymentGatewayTest {

	private static final String API = "https://api.mercadopago.test";
	private static final String TOKEN = "TEST-access-token";
	private static final String SECRET = "webhook-secret";

	private MockRestServiceServer server;
	private MercadoPagoPaymentGateway gateway;

	@BeforeEach
	void setUp() {
		gateway = gateway(TOKEN, SECRET);
	}

	private MercadoPagoPaymentGateway gateway(String token, String secret) {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		return new MercadoPagoPaymentGateway(
			new MercadoPagoProperties(token, secret, API, "https://shop.test/api/billing/webhooks/mercadopago"),
			builder
		);
	}

	/** Signs a webhook the way Mercado Pago does. */
	private static Map<String, String> signedHeaders(String dataId, String requestId, String ts) {
		String v1 = MercadoPagoPaymentGateway.hmacSha256Hex(SECRET, MercadoPagoPaymentGateway.manifest(dataId, requestId, ts));
		Map<String, String> headers = new HashMap<>();
		headers.put("x-signature", "ts=" + ts + ",v1=" + v1);
		if (requestId != null) headers.put("x-request-id", requestId);
		return headers;
	}

	private static Map<String, String> paymentQuery(String dataId) {
		return Map.of("data.id", dataId, "type", "payment");
	}

	private static String paymentJson(String id, String status, String externalReference) {
		return """
			{"id": %s, "status": "%s", "status_detail": "detail-%s", "external_reference": %s}
			""".formatted(id, status, status, externalReference == null ? "null" : "\"" + externalReference + "\"");
	}

	@Test
	void shouldIdentifyItsProvider() {
		assertThat(gateway.provider()).isEqualTo(PaymentProviderEnum.MERCADO_PAGO);
	}

	@Nested
	class CreateCharge {

		private final UUID paymentId = UUID.randomUUID();
		private final UUID serviceOrderId = UUID.randomUUID();
		private final ChargeRequest charge = new ChargeRequest(
			paymentId,
			serviceOrderId,
			new BigDecimal("241.00"),
			"Ordem de serviço - veículo ABC1D23",
			"Cliente",
			"cliente@test.com"
		);

		@Test
		void shouldCreateAPreferenceAndReturnItsCheckoutUrl() {
			server
				.expect(requestTo(API + "/checkout/preferences"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer " + TOKEN))
				.andExpect(header("X-Idempotency-Key", paymentId.toString()))
				.andExpect(jsonPath("$.external_reference").value(paymentId.toString()))
				.andExpect(jsonPath("$.notification_url").value("https://shop.test/api/billing/webhooks/mercadopago"))
				.andExpect(jsonPath("$.items[0].title").value("Ordem de serviço - veículo ABC1D23"))
				.andExpect(jsonPath("$.items[0].quantity").value(1))
				.andExpect(jsonPath("$.items[0].currency_id").value("BRL"))
				.andExpect(jsonPath("$.items[0].unit_price").value(241.00))
				.andExpect(jsonPath("$.payer.email").value("cliente@test.com"))
				.andRespond(
					withSuccess(
						"{\"id\":\"PREF-1\",\"init_point\":\"https://mp.test/checkout?pref_id=PREF-1\"}",
						MediaType.APPLICATION_JSON
					)
				);

			var result = gateway.createCharge(charge);

			assertThat(result.providerReference()).isEqualTo("PREF-1");
			assertThat(result.checkoutUrl()).isEqualTo("https://mp.test/checkout?pref_id=PREF-1");
			server.verify();
		}

		@Test
		void shouldFallBackToTheSandboxCheckoutUrl() {
			server
				.expect(requestTo(API + "/checkout/preferences"))
				.andRespond(withSuccess("{\"id\":\"PREF-2\",\"sandbox_init_point\":\"https://sandbox.test\"}", MediaType.APPLICATION_JSON));

			assertThat(gateway.createCharge(charge).checkoutUrl()).isEqualTo("https://sandbox.test");
		}

		@Test
		void shouldRefuseAPreferenceWithoutCheckoutUrl() {
			server.expect(requestTo(API + "/checkout/preferences")).andRespond(withSuccess("{\"id\":\"PREF-3\"}", MediaType.APPLICATION_JSON));

			assertThatThrownBy(() -> gateway.createCharge(charge))
				.isInstanceOf(PaymentGatewayException.class)
				.hasMessageContaining("without id or checkout URL");
		}

		@Test
		void shouldTranslateAnErrorFromTheGateway() {
			server
				.expect(requestTo(API + "/checkout/preferences"))
				.andRespond(withBadRequest().body("{\"message\":\"invalid unit_price\"}").contentType(MediaType.APPLICATION_JSON));

			assertThatThrownBy(() -> gateway.createCharge(charge))
				.isInstanceOf(PaymentGatewayException.class)
				.hasMessageContaining("HTTP 400")
				.hasMessageContaining("invalid unit_price");
		}

		@Test
		void shouldRejectAnInvalidAmountWithoutCallingTheGateway() {
			var zero = new ChargeRequest(paymentId, serviceOrderId, BigDecimal.ZERO, "OS", "Cliente", "c@test.com");

			assertThatThrownBy(() -> gateway.createCharge(zero)).isInstanceOf(PaymentGatewayException.class);
			server.verify();
		}

		@Test
		void shouldRequireTheAccessToken() {
			var unconfigured = gateway("", SECRET);

			assertThat(unconfigured.isConfigured()).isFalse();
			assertThatThrownBy(() -> unconfigured.createCharge(charge))
				.isInstanceOf(PaymentGatewayException.class)
				.hasMessageContaining("MERCADOPAGO_ACCESS_TOKEN");
		}
	}

	@Nested
	class Notifications {

		private final UUID paymentId = UUID.randomUUID();

		@Test
		void shouldVerifyTheSignatureAndReadThePaymentFromTheApi() {
			server
				.expect(requestTo(API + "/v1/payments/123456"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("Authorization", "Bearer " + TOKEN))
				.andRespond(withSuccess(paymentJson("123456", "approved", paymentId.toString()), MediaType.APPLICATION_JSON));

			var notification = gateway.parseNotification(
				signedHeaders("123456", "req-1", "1704908010"),
				paymentQuery("123456"),
				"{\"type\":\"payment\",\"data\":{\"id\":\"123456\"}}"
			);

			assertThat(notification).contains(
				new GatewayNotification(paymentId, "123456", GatewayPaymentStatusEnum.APPROVED, "Mercado Pago: approved (detail-approved)")
			);
			server.verify();
		}

		@Test
		void shouldAcceptASignatureWithoutRequestId() {
			server
				.expect(requestTo(API + "/v1/payments/77"))
				.andRespond(withSuccess(paymentJson("77", "rejected", paymentId.toString()), MediaType.APPLICATION_JSON));

			var notification = gateway.parseNotification(signedHeaders("77", null, "1704908010"), paymentQuery("77"), "");

			assertThat(notification).get().extracting(GatewayNotification::status).isEqualTo(GatewayPaymentStatusEnum.REJECTED);
		}

		@Test
		void shouldTakeTheTypeAndIdFromTheBodyWhenTheQueryHasNone() {
			server
				.expect(requestTo(API + "/v1/payments/88"))
				.andRespond(withSuccess(paymentJson("88", "approved", paymentId.toString()), MediaType.APPLICATION_JSON));

			var notification = gateway.parseNotification(
				signedHeaders(null, "req-2", "1704908010"),
				Map.of(),
				"{\"type\":\"payment\",\"data\":{\"id\":\"88\"}}"
			);

			assertThat(notification).get().extracting(GatewayNotification::providerPaymentId).isEqualTo("88");
		}

		@Test
		void shouldRefuseAWrongSignature() {
			Map<String, String> headers = signedHeaders("123", "req-1", "1704908010");

			// Same signature, different payment id: the manifest no longer matches.
			assertThatThrownBy(() -> gateway.parseNotification(headers, paymentQuery("999"), "{}"))
				.isInstanceOf(InvalidWebhookSignatureException.class)
				.hasMessageContaining("Invalid x-signature");
			server.verify();
		}

		@Test
		void shouldRefuseAMissingOrMalformedSignature() {
			assertThatThrownBy(() -> gateway.parseNotification(Map.of(), paymentQuery("1"), "{}"))
				.isInstanceOf(InvalidWebhookSignatureException.class)
				.hasMessageContaining("Missing");
			assertThatThrownBy(() -> gateway.parseNotification(Map.of("x-signature", "v1=abc"), paymentQuery("1"), "{}"))
				.isInstanceOf(InvalidWebhookSignatureException.class)
				.hasMessageContaining("Malformed");
			assertThatThrownBy(() -> gateway.parseNotification(Map.of("x-signature", "ts=abc,v1=abc"), paymentQuery("1"), "{}"))
				.isInstanceOf(InvalidWebhookSignatureException.class);
		}

		@Test
		void shouldRequireTheWebhookSecret() {
			var noSecret = gateway(TOKEN, "");

			assertThatThrownBy(() -> noSecret.parseNotification(signedHeaders("1", "r", "1"), paymentQuery("1"), "{}"))
				.isInstanceOf(PaymentGatewayException.class)
				.isNotInstanceOf(InvalidWebhookSignatureException.class)
				.hasMessageContaining("secret");
		}

		@Test
		void shouldIgnoreNotificationsThatAreNotAboutPayments() {
			var result = gateway.parseNotification(
				signedHeaders("55", "req-1", "1704908010"),
				Map.of("data.id", "55", "type", "merchant_order"),
				"{}"
			);

			assertThat(result).isEmpty();
			server.verify();
		}

		@Test
		void shouldIgnoreLegacyIpnCallsWithoutCheckingThem() {
			assertThat(gateway.parseNotification(Map.of(), Map.of("topic", "payment", "id", "1"), "")).isEmpty();
			server.verify();
		}

		@Test
		void shouldIgnoreAPaymentThatDoesNotReferenceABillingPayment() {
			server.expect(requestTo(API + "/v1/payments/66")).andRespond(withSuccess(paymentJson("66", "approved", "MP0001"), MediaType.APPLICATION_JSON));

			assertThat(gateway.parseNotification(signedHeaders("66", "r", "1"), paymentQuery("66"), "")).isEmpty();
		}

		@Test
		void shouldRefuseASignedPaymentNotificationWithoutId() {
			assertThatThrownBy(() -> gateway.parseNotification(signedHeaders(null, "r", "1"), Map.of("type", "payment"), "{}"))
				.isInstanceOf(PaymentGatewayException.class)
				.hasMessageContaining("data.id");
		}

		@Test
		void shouldTranslateAFailedPaymentLookup() {
			server.expect(requestTo(API + "/v1/payments/44")).andRespond(withServerError());

			assertThatThrownBy(() -> gateway.parseNotification(signedHeaders("44", "r", "1"), paymentQuery("44"), ""))
				.isInstanceOf(PaymentGatewayException.class)
				.hasMessageContaining("getPayment");
		}

		@Test
		void shouldRefuseUnreadableJson() {
			assertThatThrownBy(() -> gateway.parseNotification(signedHeaders(null, "r", "1"), Map.of(), "not json"))
				.isInstanceOf(PaymentGatewayException.class)
				.hasMessageContaining("JSON");
		}
	}

	@Nested
	class Refund {

		@Test
		void shouldRefundTheFullAmountWithAnIdempotencyKey() {
			server
				.expect(requestTo(API + "/v1/payments/123456/refunds"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer " + TOKEN))
				.andExpect(header("X-Idempotency-Key", "refund-abc"))
				.andExpect(content().string(""))
				.andRespond(withSuccess("{\"id\":1,\"status\":\"approved\"}", MediaType.APPLICATION_JSON));

			gateway.refund("123456", new BigDecimal("241.00"), "refund-abc");

			server.verify();
		}

		@Test
		void shouldTranslateARefusedRefund() {
			server
				.expect(requestTo(API + "/v1/payments/9/refunds"))
				.andRespond(withBadRequest().body("{\"message\":\"Payment already refunded\"}"));

			assertThatThrownBy(() -> gateway.refund("9", BigDecimal.TEN, "k"))
				.isInstanceOf(PaymentGatewayException.class)
				.hasMessageContaining("already refunded");
		}

		@Test
		void shouldRequireTheMercadoPagoPaymentId() {
			assertThatThrownBy(() -> gateway.refund(null, BigDecimal.TEN, "k")).isInstanceOf(PaymentGatewayException.class);
		}
	}

	@Nested
	class Mapping {

		@ParameterizedTest
		@CsvSource(
			{
				"approved,APPROVED",
				"APPROVED,APPROVED",
				"rejected,REJECTED",
				"cancelled,REJECTED",
				"pending,PENDING",
				"in_process,PENDING",
				"refunded,PENDING",
				"charged_back,PENDING",
			}
		)
		void shouldMapEveryMercadoPagoStatus(String status, GatewayPaymentStatusEnum expected) {
			assertThat(MercadoPagoPaymentGateway.mapStatus(status)).isEqualTo(expected);
		}

		@Test
		void shouldTreatAMissingStatusAsPending() {
			assertThat(MercadoPagoPaymentGateway.mapStatus(null)).isEqualTo(GatewayPaymentStatusEnum.PENDING);
		}

		@Test
		void shouldBuildTheManifestLikeTheOfficialSdk() {
			assertThat(MercadoPagoPaymentGateway.manifest("123", "req", "1704908010")).isEqualTo("id:123;request-id:req;ts:1704908010;");
			assertThat(MercadoPagoPaymentGateway.manifest(null, null, "1")).isEqualTo("ts:1;");
		}

		@Test
		void shouldComputeTheKnownHmacSha256() {
			// RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?".
			assertThat(MercadoPagoPaymentGateway.hmacSha256Hex("Jefe", "what do ya want for nothing?")).isEqualTo(
				"5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
			);
		}
	}
}
