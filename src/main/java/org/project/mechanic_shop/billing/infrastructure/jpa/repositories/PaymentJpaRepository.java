package org.project.mechanic_shop.billing.infrastructure.jpa.repositories;

import java.util.Optional;
import java.util.UUID;
import org.project.mechanic_shop.billing.infrastructure.jpa.entities.PaymentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, UUID> {
	Optional<PaymentJpaEntity> findByServiceOrderId(UUID serviceOrderId);
}
