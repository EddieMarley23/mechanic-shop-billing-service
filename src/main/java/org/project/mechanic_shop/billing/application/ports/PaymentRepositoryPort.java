package org.project.mechanic_shop.billing.application.ports;

import java.util.Optional;
import java.util.UUID;
import org.project.mechanic_shop.billing.domain.entities.payment.Payment;

public interface PaymentRepositoryPort {
	Payment save(Payment payment);
	Optional<Payment> findById(UUID id);
	Optional<Payment> findByServiceOrderId(UUID serviceOrderId);
}
