package org.project.mechanic_shop.billing.application.saga;

/** Message types this participant consumes (commands) and produces (events). */
public final class SagaMessageTypes {

	// Commands sent by the orchestrator
	public static final String BUDGET_GENERATE = "billing.budget.generate";
	public static final String BUDGET_CANCEL = "billing.budget.cancel";
	public static final String PAYMENT_CREATE = "billing.payment.create";
	public static final String PAYMENT_REFUND = "billing.payment.refund";

	// Events sent back to the orchestrator
	public static final String BUDGET_SENT = "billing.budget.sent";
	public static final String BUDGET_FAILED = "billing.budget.failed";
	public static final String BUDGET_APPROVED = "billing.budget.approved";
	public static final String BUDGET_REJECTED = "billing.budget.rejected";
	public static final String PAYMENT_CREATED = "billing.payment.created";
	public static final String PAYMENT_CONFIRMED = "billing.payment.confirmed";
	public static final String PAYMENT_FAILED = "billing.payment.failed";
	public static final String PAYMENT_REFUNDED = "billing.payment.refunded";

	private SagaMessageTypes() {}
}
