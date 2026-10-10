package org.project.mechanic_shop.billing.infrastructure.adapters;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.project.mechanic_shop.billing.application.ports.PaymentRepositoryPort;
import org.project.mechanic_shop.billing.domain.entities.payment.Payment;
import org.project.mechanic_shop.billing.infrastructure.jpa.mappers.PaymentJpaMapper;
import org.project.mechanic_shop.billing.infrastructure.jpa.repositories.PaymentJpaRepository;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentRepositoryAdapter implements PaymentRepositoryPort {

	private final PaymentJpaRepository jpaRepository;
	private final PaymentJpaMapper mapper;

	@Override
	public Payment save(Payment payment) {
		return mapper.toDomain(jpaRepository.save(mapper.toJpa(payment)));
	}

	@Override
	public Optional<Payment> findById(UUID id) {
		return jpaRepository.findById(id).map(mapper::toDomain);
	}

	@Override
	public Optional<Payment> findByServiceOrderId(UUID serviceOrderId) {
		return jpaRepository.findByServiceOrderId(serviceOrderId).map(mapper::toDomain);
	}
}
