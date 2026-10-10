package org.project.mechanic_shop.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayNotification;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayPaymentStatusEnum;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGatewayException;
import org.project.mechanic_shop.billing.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.billing.domain.entities.payment.Payment;
import org.project.mechanic_shop.billing.domain.enums.PaymentProviderEnum;
import org.project.mechanic_shop.billing.domain.enums.PaymentStatusEnum;
import org.project.mechanic_shop.billing.domain.events.PaymentCreatedEvent;
import org.project.mechanic_shop.billing.support.BillingTestHarness;
import org.springframework.security.access.AccessDeniedException;

/** Edge cases of the payment use cases. The main flows are specified in Gherkin. */
class PaymentServiceTest {

	private BillingTestHarness harness;

	@BeforeEach
	void setup() {
		harness = new BillingTestHarness();
	}

	private void approvedBudget() {
		harness.budgetService.generate(harness.sagaId, harness.serviceOrderId, harness.budgetPayload());
		harness.budgetService.respond(harness.serviceOrderId, true, BillingTestHarness.CUSTOMER);
	}

	private Payment pendingPayment() {
		approvedBudget();
		harness.paymentService.createForOrder(harness.sagaId, harness.serviceOrderId);
		return harness.payment();
	}

	private GatewayNotification notification(Payment payment, GatewayPaymentStatusEnum status) {
		return new GatewayNotification(payment.getId(), "GW-PAY-1", status, null);
	}

	@Test
	void shouldCreateAPendingPaymentWithTheCheckoutUrlAndNotifyTheCustomer() {
		Payment payment = pendingPayment();

		assertThat(payment.getStatus()).isEqualTo(PaymentStatusEnum.PENDING);
		assertThat(payment.getAmount()).isEqualByComparingTo("420.00");
		assertThat(payment.getProvider()).isEqualTo(PaymentProviderEnum.SIMULATED);
		assertThat(payment.getProviderReference()).isEqualTo("REF-" + payment.getId());
		assertThat(payment.getCheckoutUrl()).isEqualTo("https://pay.test/" + payment.getId());
		assertThat(payment.getBudgetId()).isEqualTo(harness.budget().getId());
		assertThat(harness.applicationEvents).hasAtLeastOneElementOfType(PaymentCreatedEvent.class);
		assertThat(harness.lastEvent(SagaMessageTypes.PAYMENT_CREATED).payload()).containsEntry(
			"checkoutUrl",
			payment.getCheckoutUrl()
		);
	}

	@Test
	void shouldNotChargeTwiceWhenTheCommandIsRepeated() {
		pendingPayment();

		harness.paymentService.createForOrder(harness.sagaId, harness.serviceOrderId);

		assertThat(harness.gateway.charges).hasSize(1);
		assertThat(harness.paymentRepository.count()).isEqualTo(1);
	}

	@Test
	void shouldReportFailureWhenThereIsNoApprovedBudget() {
		harness.budgetService.generate(harness.sagaId, harness.serviceOrderId, harness.budgetPayload());

		harness.paymentService.createForOrder(harness.sagaId, harness.serviceOrderId);

		assertThat(harness.paymentRepository.count()).isZero();
		assertThat(harness.gateway.charges).isEmpty();
		assertThat(harness.lastEvent(SagaMessageTypes.PAYMENT_FAILED).payload().get("reason").toString()).contains(
			"no approved budget"
		);
	}

	@Test
	void shouldReportFailureWhenTheGatewayRefusesTheCharge() {
		approvedBudget();
		harness.gateway.refuseCharges = true;

		harness.paymentService.createForOrder(harness.sagaId, harness.serviceOrderId);

		Payment payment = harness.payment();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatusEnum.FAILED);
		assertThat(payment.getFailureReason()).isEqualTo("Gateway unavailable");
		assertThat(harness.lastEvent(SagaMessageTypes.PAYMENT_FAILED).payload()).containsEntry(
			"reason",
			"Gateway unavailable"
		);
		assertThat(harness.sagaEventTypes()).doesNotContain(SagaMessageTypes.PAYMENT_CREATED);
	}

	@Test
	void shouldIgnoreAPendingNotification() {
		Payment payment = pendingPayment();

		harness.paymentService.applyNotification(notification(payment, GatewayPaymentStatusEnum.PENDING));

		assertThat(harness.payment().getStatus()).isEqualTo(PaymentStatusEnum.PENDING);
		assertThat(harness.sagaEventTypes()).doesNotContain(
			SagaMessageTypes.PAYMENT_CONFIRMED,
			SagaMessageTypes.PAYMENT_FAILED
		);
	}

	@Test
	void shouldIgnoreARepeatedNotification() {
		Payment payment = pendingPayment();
		harness.paymentService.applyNotification(notification(payment, GatewayPaymentStatusEnum.APPROVED));

		harness.paymentService.applyNotification(notification(payment, GatewayPaymentStatusEnum.APPROVED));
		harness.paymentService.applyNotification(notification(payment, GatewayPaymentStatusEnum.REJECTED));

		assertThat(harness.payment().getStatus()).isEqualTo(PaymentStatusEnum.CONFIRMED);
		assertThat(harness.sagaEventTypes()).containsOnlyOnce(SagaMessageTypes.PAYMENT_CONFIRMED);
		assertThat(harness.sagaEventTypes()).doesNotContain(SagaMessageTypes.PAYMENT_FAILED);
	}

	@Test
	void shouldUseADefaultReasonWhenTheGatewayGivesNone() {
		Payment payment = pendingPayment();

		harness.paymentService.applyNotification(notification(payment, GatewayPaymentStatusEnum.REJECTED));

		assertThat(harness.payment().getFailureReason()).isEqualTo("Payment rejected by the gateway.");
	}

	@Test
	void shouldFailForANotificationOfAnUnknownPayment() {
		var notification = new GatewayNotification(UUID.randomUUID(), "GW", GatewayPaymentStatusEnum.APPROVED, null);

		assertThatThrownBy(() -> harness.paymentService.applyNotification(notification)).isInstanceOf(
			EntityNotFoundException.class
		);
	}

	@Test
	void shouldRefundStraightAwayAChargePaidAfterItsSagaWasCompensated() {
		Payment payment = pendingPayment();
		harness.paymentService.cancelPending(harness.serviceOrderId);

		harness.paymentService.applyNotification(notification(payment, GatewayPaymentStatusEnum.APPROVED));

		assertThat(harness.payment().getStatus()).isEqualTo(PaymentStatusEnum.REFUNDED);
		assertThat(harness.payment().getProviderPaymentId()).isEqualTo("GW-PAY-1");
		assertThat(harness.gateway.refundedPaymentIds).containsExactly("GW-PAY-1");
		assertThat(harness.sagaEventTypes()).doesNotContain(SagaMessageTypes.PAYMENT_CONFIRMED);
	}

	@Test
	void shouldSimulateTheGatewayAnswer() {
		pendingPayment();

		Payment approved = harness.paymentService.simulate(harness.serviceOrderId, true, BillingTestHarness.CUSTOMER);

		assertThat(approved.getStatus()).isEqualTo(PaymentStatusEnum.CONFIRMED);
		assertThat(approved.getProviderPaymentId()).startsWith("SIM-PAY-");
	}

	@Test
	void shouldSimulateARejection() {
		pendingPayment();

		Payment rejected = harness.paymentService.simulate(harness.serviceOrderId, false, BillingTestHarness.ADMIN);

		assertThat(rejected.getStatus()).isEqualTo(PaymentStatusEnum.FAILED);
		assertThat(rejected.getFailureReason()).isEqualTo("Payment rejected by the simulated gateway.");
	}

	@Test
	void shouldRefuseSimulationWhenARealGatewayIsConfigured() {
		pendingPayment();
		harness.gateway.provider = PaymentProviderEnum.MERCADO_PAGO;

		assertThatThrownBy(() -> harness.paymentService.simulate(harness.serviceOrderId, true, BillingTestHarness.ADMIN))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("simulated gateway");
		assertThat(harness.payment().getStatus()).isEqualTo(PaymentStatusEnum.PENDING);
	}

	@Test
	void shouldLetOnlyTheOwnerOrStaffSeeThePayment() {
		pendingPayment();

		assertThat(harness.paymentService.findByServiceOrderId(harness.serviceOrderId, BillingTestHarness.CUSTOMER)).isNotNull();
		assertThat(harness.paymentService.findByServiceOrderId(harness.serviceOrderId, BillingTestHarness.ADMIN)).isNotNull();
		assertThatThrownBy(() ->
			harness.paymentService.findByServiceOrderId(harness.serviceOrderId, BillingTestHarness.OTHER_CUSTOMER)
		).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> harness.paymentService.simulate(harness.serviceOrderId, true, BillingTestHarness.OTHER_CUSTOMER))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void shouldFailWhenLookingUpAPaymentThatWasNotCreated() {
		assertThatThrownBy(() -> harness.paymentService.findByServiceOrderId(UUID.randomUUID(), BillingTestHarness.ADMIN))
			.isInstanceOf(EntityNotFoundException.class);
	}

	@Test
	void shouldAnswerTheRefundCommandEvenWhenThereIsNothingToRefund() {
		pendingPayment();

		harness.paymentService.refund(harness.sagaId, harness.serviceOrderId, "x");

		assertThat(harness.gateway.refundedPaymentIds).isEmpty();
		assertThat(harness.payment().getStatus()).isEqualTo(PaymentStatusEnum.PENDING);
		assertThat(harness.lastEvent(SagaMessageTypes.PAYMENT_REFUNDED).payload()).containsEntry("refunded", false);
	}

	@Test
	void shouldNotAnswerTheRefundCommandWhenTheGatewayRefusesSoItIsRetried() {
		Payment payment = pendingPayment();
		harness.paymentService.applyNotification(notification(payment, GatewayPaymentStatusEnum.APPROVED));
		harness.gateway.refuseRefunds = true;

		assertThatThrownBy(() -> harness.paymentService.refund(harness.sagaId, harness.serviceOrderId, "x")).isInstanceOf(
			PaymentGatewayException.class
		);

		assertThat(harness.payment().getStatus()).isEqualTo(PaymentStatusEnum.CONFIRMED);
		assertThat(harness.sagaEventTypes()).doesNotContain(SagaMessageTypes.PAYMENT_REFUNDED);
	}

	@Test
	void shouldLeaveASettledPaymentAloneWhenCancelingPendingCharges() {
		Payment payment = pendingPayment();
		harness.paymentService.applyNotification(notification(payment, GatewayPaymentStatusEnum.APPROVED));

		harness.paymentService.cancelPending(harness.serviceOrderId);
		harness.paymentService.cancelPending(UUID.randomUUID());

		assertThat(harness.payment().getStatus()).isEqualTo(PaymentStatusEnum.CONFIRMED);
	}
}
