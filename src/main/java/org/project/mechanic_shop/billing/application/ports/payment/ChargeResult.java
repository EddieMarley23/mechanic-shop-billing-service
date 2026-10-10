package org.project.mechanic_shop.billing.application.ports.payment;

/**
 * @param providerReference identifier of the charge at the gateway (Mercado Pago: preference id)
 * @param checkoutUrl where the customer completes the payment (Mercado Pago: {@code init_point})
 */
public record ChargeResult(String providerReference, String checkoutUrl) {}
