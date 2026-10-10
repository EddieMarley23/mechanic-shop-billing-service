# language: pt
Funcionalidade: Orçamento e pagamento da ordem de serviço
  Como serviço de cobrança da oficina
  Quero gerar o orçamento, registrar a resposta do cliente e cobrar o valor aprovado
  Para que a ordem de serviço só vá para execução depois de aprovada e paga

  Cenário: Fluxo completo, do orçamento ao pagamento confirmado
    Quando a saga solicita um orçamento de "420.00" reais para a ordem de serviço
    Então o orçamento fica "SENT"
    E a saga é informada de "billing.budget.sent"
    E o cliente recebe o orçamento com os links de aprovar e recusar

    Quando o cliente aprova o orçamento
    Então o orçamento fica "APPROVED"
    E a saga é informada de "billing.budget.approved"

    Quando a saga solicita o pagamento
    Então uma cobrança de "420.00" reais é criada no gateway de pagamento
    E o pagamento fica "PENDING"
    E a saga é informada de "billing.payment.created"

    Quando o gateway confirma o pagamento
    Então o pagamento fica "CONFIRMED"
    E a saga é informada de "billing.payment.confirmed"

  Cenário: Cliente recusa o orçamento
    Dado um orçamento enviado ao cliente
    Quando o cliente recusa o orçamento
    Então o orçamento fica "REJECTED"
    E a saga é informada de "billing.budget.rejected"

  Cenário: Cliente responde pelo link recebido por e-mail, que só vale uma vez
    Dado um orçamento enviado ao cliente
    Quando o cliente aprova pelo link do e-mail
    Então o orçamento fica "APPROVED"
    E o link do e-mail deixa de funcionar

  Cenário: Outro cliente não pode responder ao orçamento
    Dado um orçamento enviado ao cliente
    Quando outro cliente tenta aprovar o orçamento
    Então o acesso é negado
    E o orçamento fica "SENT"

  Cenário: Pagamento recusado pelo gateway
    Dado um pagamento aguardando o cliente
    Quando o gateway recusa o pagamento
    Então o pagamento fica "FAILED"
    E a saga é informada de "billing.payment.failed"

  Cenário: Compensação estorna o pagamento confirmado
    Dado um pagamento confirmado
    Quando a saga solicita o estorno do pagamento
    Então o valor é devolvido pelo gateway de pagamento
    E o pagamento fica "REFUNDED"
    E a saga é informada de "billing.payment.refunded"

  Cenário: Compensação cancela o orçamento e a cobrança ainda não paga
    Dado um pagamento aguardando o cliente
    Quando a saga solicita o cancelamento do orçamento
    Então o orçamento fica "CANCELED"
    E o pagamento fica "CANCELED"

  Cenário: Um comando entregue duas vezes não gera dois orçamentos
    Quando o mesmo pedido de orçamento é entregue duas vezes
    Então existe um único orçamento para a ordem de serviço
    E a saga é informada de "billing.budget.sent" uma única vez
