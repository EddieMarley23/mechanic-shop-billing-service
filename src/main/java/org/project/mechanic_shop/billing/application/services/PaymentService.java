package org.project.mechanic_shop.billing.application.services;

import java.util.UUID;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayNotification;
import org.project.mechanic_shop.billing.domain.entities.payment.Payment;

public interface PaymentService {
	void createForOrder(UUID sagaId, UUID serviceOrderId);
	void applyNotification(GatewayNotification notification);
	Payment simulate(UUID serviceOrderId, boolean approved, Requester requester);
	void refund(UUID sagaId, UUID serviceOrderId, String reason);
	void cancelPending(UUID serviceOrderId);
	Payment findByServiceOrderId(UUID serviceOrderId, Requester requester);
}
