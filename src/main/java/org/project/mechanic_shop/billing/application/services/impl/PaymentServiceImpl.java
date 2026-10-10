package org.project.mechanic_shop.billing.application.services.impl;

import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.billing.application.ports.BudgetRepositoryPort;
import org.project.mechanic_shop.billing.application.ports.PaymentRepositoryPort;
import org.project.mechanic_shop.billing.application.ports.SagaEventPublisher;
import org.project.mechanic_shop.billing.application.ports.payment.ChargeRequest;
import org.project.mechanic_shop.billing.application.ports.payment.ChargeResult;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayNotification;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayPaymentStatusEnum;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGateway;
import org.project.mechanic_shop.billing.application.ports.payment.PaymentGatewayException;
import org.project.mechanic_shop.billing.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.billing.application.services.PaymentService;
import org.project.mechanic_shop.billing.application.services.Requester;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.project.mechanic_shop.billing.domain.entities.payment.Payment;
import org.project.mechanic_shop.billing.domain.enums.BudgetStatusEnum;
import org.project.mechanic_shop.billing.domain.enums.PaymentProviderEnum;
import org.project.mechanic_shop.billing.domain.enums.PaymentStatusEnum;
import org.project.mechanic_shop.billing.domain.events.PaymentCreatedEvent;
import org.project.mechanic_shop.billing.shared.config.observability.ObservabilityReporter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

	private final PaymentRepositoryPort paymentRepository;
	private final BudgetRepositoryPort budgetRepository;
	private final PaymentGateway paymentGateway;
	private final SagaEventPublisher sagaEventPublisher;
	private final ApplicationEventPublisher eventPublisher;
	private final ObservabilityReporter observabilityReporter;
	private final Clock clock;

	private static final String PAYMENT_NOT_FOUND_MSG = "Payment not found for Service Order: ";
	private static final String REASON_KEY = "reason";
	private static final String PAYMENT_ID_KEY = "paymentId";

	@Override
	@Transactional
	public void createForOrder(UUID sagaId, UUID serviceOrderId) {
		if (paymentRepository.findByServiceOrderId(serviceOrderId).isPresent()) {
			log.info("Payment already created for Service Order {}. Ignoring duplicated command.", serviceOrderId);
			return;
		}

		Optional<Budget> approvedBudget = budgetRepository
			.findByServiceOrderId(serviceOrderId)
			.filter(b -> b.getStatus() == BudgetStatusEnum.APPROVED);

		if (approvedBudget.isEmpty()) {
			log.warn("Cannot charge Service Order {}: there is no approved budget.", serviceOrderId);
			sagaEventPublisher.publish(
				sagaId,
				serviceOrderId,
				SagaMessageTypes.PAYMENT_FAILED,
				Map.of(REASON_KEY, "There is no approved budget to charge.")
			);
			return;
		}

		Budget budget = approvedBudget.get();
		Instant now = Instant.now(clock);

		Payment payment = new Payment();
		payment.setId(UUID.randomUUID());
		payment.setBudgetId(budget.getId());
		payment.setServiceOrderId(serviceOrderId);
		payment.setSagaId(sagaId);
		payment.setAmount(budget.getTotalAmount());
		payment.setProvider(paymentGateway.provider());
		payment.setCreatedAt(now);
		payment.setUpdatedAt(now);

		try {
			ChargeResult charge = paymentGateway.createCharge(
				new ChargeRequest(
					payment.getId(),
					serviceOrderId,
					payment.getAmount(),
					"Ordem de serviço " + serviceOrderId + " - veículo " + budget.getLicensePlate(),
					budget.getCustomerName(),
					budget.getCustomerEmail()
				)
			);
			payment.setProviderReference(charge.providerReference());
			payment.setCheckoutUrl(charge.checkoutUrl());
		} catch (PaymentGatewayException e) {
			log.error("Payment gateway refused the charge for Service Order {}: {}", serviceOrderId, e.getMessage());
			observabilityReporter.recordIntegrationFailure(paymentGateway.provider().name(), "createCharge", e);

			payment.fail(e.getMessage(), now);
			Payment failed = paymentRepository.save(payment);
			sagaEventPublisher.publish(
				sagaId,
				serviceOrderId,
				SagaMessageTypes.PAYMENT_FAILED,
				Map.of(PAYMENT_ID_KEY, failed.getId(), REASON_KEY, String.valueOf(e.getMessage()))
			);
			return;
		}

		Payment saved = paymentRepository.save(payment);

		sagaEventPublisher.publish(
			sagaId,
			serviceOrderId,
			SagaMessageTypes.PAYMENT_CREATED,
			Map.of(PAYMENT_ID_KEY, saved.getId(), "checkoutUrl", String.valueOf(saved.getCheckoutUrl()))
		);
		eventPublisher.publishEvent(new PaymentCreatedEvent(saved, budget));

		log.info("Payment {} created for Service Order {}. Amount: R$ {}", saved.getId(), serviceOrderId, saved.getAmount());
	}

	@Override
	@Transactional
	public void applyNotification(GatewayNotification notification) {
		Payment payment = paymentRepository
			.findById(notification.paymentId())
			.orElseThrow(() -> new EntityNotFoundException("Payment not found: " + notification.paymentId()));

		Instant now = Instant.now(clock);

		if (notification.status() == GatewayPaymentStatusEnum.PENDING) {
			log.info("Payment {} is still pending at the gateway.", payment.getId());
			return;
		}

		if (payment.getStatus() == PaymentStatusEnum.CANCELED && notification.status() == GatewayPaymentStatusEnum.APPROVED) {
			refundLatePayment(payment, notification, now);
			return;
		}

		if (!payment.isPending()) {
			log.info("Payment {} already settled as {}. Ignoring notification.", payment.getId(), payment.getStatus());
			return;
		}

		if (notification.status() == GatewayPaymentStatusEnum.APPROVED) {
			payment.confirm(notification.providerPaymentId(), now);
			paymentRepository.save(payment);
			sagaEventPublisher.publish(
				payment.getSagaId(),
				payment.getServiceOrderId(),
				SagaMessageTypes.PAYMENT_CONFIRMED,
				Map.of(PAYMENT_ID_KEY, payment.getId())
			);
			log.info("Payment {} confirmed for Service Order {}.", payment.getId(), payment.getServiceOrderId());
			return;
		}

		String reason = notification.detail() != null ? notification.detail() : "Payment rejected by the gateway.";
		payment.fail(reason, now);
		paymentRepository.save(payment);
		sagaEventPublisher.publish(
			payment.getSagaId(),
			payment.getServiceOrderId(),
			SagaMessageTypes.PAYMENT_FAILED,
			Map.of(PAYMENT_ID_KEY, payment.getId(), REASON_KEY, reason)
		);
		log.info("Payment {} rejected for Service Order {}: {}", payment.getId(), payment.getServiceOrderId(), reason);
	}

	@Override
	@Transactional
	public Payment simulate(UUID serviceOrderId, boolean approved, Requester requester) {
		if (paymentGateway.provider() != PaymentProviderEnum.SIMULATED) {
			throw new IllegalStateException("Payment simulation is only available with the simulated gateway.");
		}

		Payment payment = findByServiceOrderId(serviceOrderId, requester);

		applyNotification(
			new GatewayNotification(
				payment.getId(),
				"SIM-PAY-" + UUID.randomUUID(),
				approved ? GatewayPaymentStatusEnum.APPROVED : GatewayPaymentStatusEnum.REJECTED,
				approved ? null : "Payment rejected by the simulated gateway."
			)
		);

		return paymentRepository
			.findById(payment.getId())
			.orElseThrow(() -> new EntityNotFoundException(PAYMENT_NOT_FOUND_MSG + serviceOrderId));
	}

	@Override
	@Transactional
	public void refund(UUID sagaId, UUID serviceOrderId, String reason) {
		Optional<Payment> paid = paymentRepository
			.findByServiceOrderId(serviceOrderId)
			.filter(p -> p.getStatus() == PaymentStatusEnum.CONFIRMED);

		Map<String, Object> payload = new HashMap<>();
		payload.put("refunded", paid.isPresent());

		if (paid.isPresent()) {
			Payment payment = paid.get();
			// A gateway failure here propagates on purpose: the command is redelivered and, after the
			// retries, parked in the dead-letter queue. Money owed to a customer must not be dropped.
			paymentGateway.refund(payment.getProviderPaymentId(), payment.getAmount(), "refund-" + payment.getId());
			payment.refund(Instant.now(clock));
			paymentRepository.save(payment);
			payload.put(PAYMENT_ID_KEY, payment.getId());
			log.info("Payment {} refunded. Reason: {}", payment.getId(), reason);
		} else {
			log.info("No confirmed payment to refund for Service Order {}.", serviceOrderId);
		}

		sagaEventPublisher.publish(sagaId, serviceOrderId, SagaMessageTypes.PAYMENT_REFUNDED, payload);
	}

	@Override
	@Transactional
	public void cancelPending(UUID serviceOrderId) {
		paymentRepository
			.findByServiceOrderId(serviceOrderId)
			.filter(Payment::isPending)
			.ifPresent(payment -> {
				payment.cancel(Instant.now(clock));
				paymentRepository.save(payment);
				log.info("Pending payment {} canceled by Saga compensation.", payment.getId());
			});
	}

	@Override
	@Transactional(readOnly = true)
	public Payment findByServiceOrderId(UUID serviceOrderId, Requester requester) {
		Payment payment = paymentRepository
			.findByServiceOrderId(serviceOrderId)
			.orElseThrow(() -> new EntityNotFoundException(PAYMENT_NOT_FOUND_MSG + serviceOrderId));

		if (requester != null && requester.isCustomer()) {
			boolean owns = budgetRepository
				.findById(payment.getBudgetId())
				.map(b -> b.belongsTo(requester.email()))
				.orElse(false);
			if (!owns) throw new AccessDeniedException("You can only access your own payments.");
		}

		return payment;
	}

	/**
	 * The customer paid a charge whose Saga had already been compensated (e.g. the payment deadline
	 * expired). The order is canceled, so the money goes straight back.
	 */
	private void refundLatePayment(Payment payment, GatewayNotification notification, Instant now) {
		log.warn("Payment {} was paid after being canceled. Refunding.", payment.getId());
		paymentGateway.refund(notification.providerPaymentId(), payment.getAmount(), "refund-" + payment.getId());
		payment.setProviderPaymentId(notification.providerPaymentId());
		payment.refund(now);
		paymentRepository.save(payment);
	}
}
