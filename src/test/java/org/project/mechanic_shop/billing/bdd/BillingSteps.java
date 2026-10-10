package org.project.mechanic_shop.billing.bdd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.Map;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayNotification;
import org.project.mechanic_shop.billing.application.ports.payment.GatewayPaymentStatusEnum;
import org.project.mechanic_shop.billing.application.saga.SagaMessage;
import org.project.mechanic_shop.billing.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.billing.domain.enums.BudgetStatusEnum;
import org.project.mechanic_shop.billing.domain.enums.PaymentStatusEnum;
import org.project.mechanic_shop.billing.domain.events.BudgetSentEvent;
import org.project.mechanic_shop.billing.support.BillingTestHarness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Steps of the Billing scenarios. The Billing use cases are the system under test; the steps act
 * as the Saga orchestrator (sending its commands), as the customer and as the payment gateway.
 */
public class BillingSteps {

	private final BillingTestHarness harness = new BillingTestHarness();
	private String emailToken;
	private Throwable failure;

	// --------------------------------------------------------------------------------------- Dado

	@Dado("um orçamento enviado ao cliente")
	public void orcamentoEnviado() {
		sagaSolicitaOrcamento("420.00");
	}

	@Dado("um pagamento aguardando o cliente")
	public void pagamentoAguardando() {
		orcamentoEnviado();
		clienteAprova();
		sagaSolicitaPagamento();
	}

	@Dado("um pagamento confirmado")
	public void pagamentoConfirmado() {
		pagamentoAguardando();
		gatewayConfirma();
	}

	// ------------------------------------------------------------------------------------- Quando

	@Quando("a saga solicita um orçamento de {string} reais para a ordem de serviço")
	public void sagaSolicitaOrcamento(String total) {
		assertThat(harness.budgetPayload().totalAmount()).isEqualByComparingTo(new BigDecimal(total));
		harness.receive(SagaMessageTypes.BUDGET_GENERATE, harness.budgetPayload());
		emailToken = harness.budget().getApprovalToken();
	}

	@Quando("o mesmo pedido de orçamento é entregue duas vezes")
	public void pedidoEntregueDuasVezes() {
		SagaMessage command = harness.command(SagaMessageTypes.BUDGET_GENERATE, harness.budgetPayload());
		harness.commandHandler.handle(command);
		harness.commandHandler.handle(command);
	}

	@Quando("o cliente aprova o orçamento")
	public void clienteAprova() {
		harness.budgetService.respond(harness.serviceOrderId, true, BillingTestHarness.CUSTOMER);
	}

	@Quando("o cliente recusa o orçamento")
	public void clienteRecusa() {
		harness.budgetService.respond(harness.serviceOrderId, false, BillingTestHarness.CUSTOMER);
	}

	@Quando("o cliente aprova pelo link do e-mail")
	public void clienteAprovaPeloLink() {
		harness.budgetService.respondByToken(emailToken, true);
	}

	@Quando("outro cliente tenta aprovar o orçamento")
	public void outroClienteTentaAprovar() {
		try {
			harness.budgetService.respond(harness.serviceOrderId, true, BillingTestHarness.OTHER_CUSTOMER);
		} catch (RuntimeException e) {
			failure = e;
		}
	}

	@Quando("a saga solicita o pagamento")
	public void sagaSolicitaPagamento() {
		harness.receive(SagaMessageTypes.PAYMENT_CREATE);
	}

	@Quando("o gateway confirma o pagamento")
	public void gatewayConfirma() {
		harness.paymentService.applyNotification(
			new GatewayNotification(harness.payment().getId(), "GW-PAY-1", GatewayPaymentStatusEnum.APPROVED, null)
		);
	}

	@Quando("o gateway recusa o pagamento")
	public void gatewayRecusa() {
		harness.paymentService.applyNotification(
			new GatewayNotification(harness.payment().getId(), "GW-PAY-1", GatewayPaymentStatusEnum.REJECTED, "Saldo insuficiente")
		);
	}

	@Quando("a saga solicita o estorno do pagamento")
	public void sagaSolicitaEstorno() {
		harness.receive(SagaMessageTypes.PAYMENT_REFUND, Map.of("reason", "Reparo inviável"));
	}

	@Quando("a saga solicita o cancelamento do orçamento")
	public void sagaSolicitaCancelamento() {
		harness.receive(SagaMessageTypes.BUDGET_CANCEL, Map.of("reason", "Prazo de pagamento expirado"));
	}

	// -------------------------------------------------------------------------------------- Então

	@Entao("o orçamento fica {string}")
	public void orcamentoFica(String status) {
		assertThat(harness.budget().getStatus()).isEqualTo(BudgetStatusEnum.valueOf(status));
	}

	@Entao("o pagamento fica {string}")
	public void pagamentoFica(String status) {
		assertThat(harness.payment().getStatus()).isEqualTo(PaymentStatusEnum.valueOf(status));
	}

	@Entao("a saga é informada de {string}")
	public void sagaInformada(String type) {
		var event = harness.lastEvent(type);
		assertThat(event.sagaId()).isEqualTo(harness.sagaId);
		assertThat(event.serviceOrderId()).isEqualTo(harness.serviceOrderId);
	}

	@Entao("a saga é informada de {string} uma única vez")
	public void sagaInformadaUmaVez(String type) {
		assertThat(harness.sagaEventTypes()).containsOnlyOnce(type);
	}

	@Entao("o cliente recebe o orçamento com os links de aprovar e recusar")
	public void clienteRecebeOrcamento() {
		assertThat(harness.applicationEvents)
			.filteredOn(BudgetSentEvent.class::isInstance)
			.singleElement()
			.satisfies(e -> assertThat(((BudgetSentEvent) e).approvalToken()).isEqualTo(emailToken).isNotBlank());
	}

	@Entao("o link do e-mail deixa de funcionar")
	public void linkDeixaDeFuncionar() {
		assertThatThrownBy(() -> harness.budgetService.respondByToken(emailToken, false)).isInstanceOf(
			EntityNotFoundException.class
		);
	}

	@Entao("o acesso é negado")
	public void acessoNegado() {
		assertThat(failure).isInstanceOf(AccessDeniedException.class);
	}

	@Entao("uma cobrança de {string} reais é criada no gateway de pagamento")
	public void cobrancaCriada(String amount) {
		assertThat(harness.gateway.charges)
			.singleElement()
			.satisfies(charge -> {
				assertThat(charge.amount()).isEqualByComparingTo(new BigDecimal(amount));
				assertThat(charge.paymentId()).isEqualTo(harness.payment().getId());
				assertThat(charge.payerEmail()).isEqualTo(BillingTestHarness.CUSTOMER_EMAIL);
			});
	}

	@Entao("o valor é devolvido pelo gateway de pagamento")
	public void valorDevolvido() {
		assertThat(harness.gateway.refundedPaymentIds).containsExactly("GW-PAY-1");
	}

	@Entao("existe um único orçamento para a ordem de serviço")
	public void umUnicoOrcamento() {
		assertThat(harness.budgetRepository.count()).isEqualTo(1);
	}
}
