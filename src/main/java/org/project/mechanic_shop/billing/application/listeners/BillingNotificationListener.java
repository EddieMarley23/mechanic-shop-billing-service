package org.project.mechanic_shop.billing.application.listeners;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.billing.application.ports.EmailService;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.project.mechanic_shop.billing.domain.events.BudgetSentEvent;
import org.project.mechanic_shop.billing.domain.events.PaymentCreatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
@RequiredArgsConstructor
public class BillingNotificationListener {

	private final EmailService emailService;

	@Value("${app.base-url}")
	private String baseUrl;

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handleBudgetSent(BudgetSentEvent event) {
		Budget budget = event.budget();
		String token = event.approvalToken();

		String approveUrl = baseUrl + "/api/billing/budgets/approval?token=" + token + "&approved=true";
		String rejectUrl = baseUrl + "/api/billing/budgets/approval?token=" + token + "&approved=false";

		StringBuilder items = new StringBuilder();
		budget
			.getItems()
			.forEach(i ->
				items
					.append("- ")
					.append(i.getQuantity())
					.append("x ")
					.append(i.getDescription())
					.append(": R$ ")
					.append(i.getTotalPrice())
					.append("\n")
			);

		String body =
			"O diagnóstico do seu veículo foi concluído e o orçamento está pronto para sua análise.\n\n" +
			"OS: " + budget.getServiceOrderId() + "\n" +
			"Veículo: " + budget.getLicensePlate() + "\n\n" +
			items +
			"\nTotal do Orçamento: R$ " + budget.getTotalAmount() + "\n\n" +
			"Clique em um dos links abaixo para responder:\n\n" +
			"✅ APROVAR: " + approveUrl + "\n\n" +
			"❌ RECUSAR: " + rejectUrl + "\n\n" +
			"Atenção: cada link pode ser utilizado apenas uma vez.";

		sendEmail(budget.getCustomerEmail(), "Ação Necessária: Orçamento Pendente de Aprovação", body);
	}

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handlePaymentCreated(PaymentCreatedEvent event) {
		String body =
			"Seu orçamento foi aprovado. Para iniciarmos o reparo, conclua o pagamento.\n\n" +
			"OS: " + event.payment().getServiceOrderId() + "\n" +
			"Valor: R$ " + event.payment().getAmount() + "\n\n" +
			"Pagar: " + event.payment().getCheckoutUrl();

		sendEmail(event.budget().getCustomerEmail(), "Pagamento Pendente: Ordem de Serviço", body);
	}

	private void sendEmail(String to, String subject, String body) {
		if (to != null) {
			emailService.sendEmail(new String[] { to }, subject, body);
		}
	}
}
