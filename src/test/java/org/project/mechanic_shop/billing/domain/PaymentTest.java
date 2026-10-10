package org.project.mechanic_shop.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.billing.domain.entities.payment.Payment;
import org.project.mechanic_shop.billing.domain.enums.PaymentStatusEnum;

class PaymentTest {

	private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");

	@Test
	void shouldConfirmKeepingTheGatewayPaymentId() {
		Payment payment = new Payment();

		payment.confirm("GW-1", NOW);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatusEnum.CONFIRMED);
		assertThat(payment.getProviderPaymentId()).isEqualTo("GW-1");
		assertThat(payment.getUpdatedAt()).isEqualTo(NOW);
		assertThat(payment.isPending()).isFalse();
	}

	@Test
	void shouldFailKeepingTheReason() {
		Payment payment = new Payment();

		payment.fail("Saldo insuficiente", NOW);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatusEnum.FAILED);
		assertThat(payment.getFailureReason()).isEqualTo("Saldo insuficiente");
	}

	@Test
	void shouldOnlySettleAPendingPayment() {
		Payment payment = new Payment();
		payment.confirm("GW-1", NOW);

		assertThatThrownBy(() -> payment.confirm("GW-2", NOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> payment.fail("x", NOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> payment.cancel(NOW)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldRefundAConfirmedPayment() {
		Payment payment = new Payment();
		payment.confirm("GW-1", NOW);

		payment.refund(NOW);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatusEnum.REFUNDED);
	}

	@Test
	void shouldRefundAChargePaidAfterBeingCanceled() {
		Payment payment = new Payment();
		payment.cancel(NOW);

		payment.refund(NOW);

		assertThat(payment.getStatus()).isEqualTo(PaymentStatusEnum.REFUNDED);
	}

	@Test
	void shouldNotRefundWhatWasNeverPaid() {
		Payment pending = new Payment();
		Payment failed = new Payment();
		failed.fail("x", NOW);

		assertThatThrownBy(() -> pending.refund(NOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> failed.refund(NOW)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldDescribeEveryStatus() {
		for (PaymentStatusEnum status : PaymentStatusEnum.values()) {
			assertThat(status.getLabel()).isNotBlank();
			assertThat(status.getDescription()).isNotBlank();
		}
	}
}
