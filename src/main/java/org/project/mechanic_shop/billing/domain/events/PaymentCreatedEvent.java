package org.project.mechanic_shop.billing.domain.events;

import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.project.mechanic_shop.billing.domain.entities.payment.Payment;

public record PaymentCreatedEvent(Payment payment, Budget budget) {}
