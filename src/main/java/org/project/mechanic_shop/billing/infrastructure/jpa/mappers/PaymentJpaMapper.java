package org.project.mechanic_shop.billing.infrastructure.jpa.mappers;

import org.project.mechanic_shop.billing.domain.entities.payment.Payment;
import org.project.mechanic_shop.billing.infrastructure.jpa.entities.PaymentJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class PaymentJpaMapper {

	public Payment toDomain(PaymentJpaEntity jpa) {
		if (jpa == null) return null;

		Payment payment = new Payment();
		payment.setId(jpa.getId());
		payment.setBudgetId(jpa.getBudgetId());
		payment.setServiceOrderId(jpa.getServiceOrderId());
		payment.setSagaId(jpa.getSagaId());
		payment.setAmount(jpa.getAmount());
		payment.setStatus(jpa.getStatus());
		payment.setProvider(jpa.getProvider());
		payment.setProviderReference(jpa.getProviderReference());
		payment.setProviderPaymentId(jpa.getProviderPaymentId());
		payment.setCheckoutUrl(jpa.getCheckoutUrl());
		payment.setFailureReason(jpa.getFailureReason());
		payment.setCreatedAt(jpa.getCreatedAt());
		payment.setUpdatedAt(jpa.getUpdatedAt());
		return payment;
	}

	public PaymentJpaEntity toJpa(Payment payment) {
		if (payment == null) return null;

		PaymentJpaEntity jpa = new PaymentJpaEntity();
		jpa.setId(payment.getId());
		jpa.setBudgetId(payment.getBudgetId());
		jpa.setServiceOrderId(payment.getServiceOrderId());
		jpa.setSagaId(payment.getSagaId());
		jpa.setAmount(payment.getAmount());
		jpa.setStatus(payment.getStatus());
		jpa.setProvider(payment.getProvider());
		jpa.setProviderReference(payment.getProviderReference());
		jpa.setProviderPaymentId(payment.getProviderPaymentId());
		jpa.setCheckoutUrl(payment.getCheckoutUrl());
		jpa.setFailureReason(payment.getFailureReason());
		jpa.setCreatedAt(payment.getCreatedAt());
		jpa.setUpdatedAt(payment.getUpdatedAt());
		return jpa;
	}
}
