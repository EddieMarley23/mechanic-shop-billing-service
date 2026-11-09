# Arquitetura do Billing Service

Microsserviço de cobrança da oficina. Participa da Saga da ordem de serviço, orquestrada pelo OS Service: gera o orçamento, registra a resposta do cliente, cria e verifica o pagamento e estorna quando a Saga é compensada. A visão do sistema inteiro (os três serviços, a Saga e a implantação) está no repositório do OS Service, em `docs/architecture/`.

## Contexto

```mermaid
flowchart LR
    customer["Cliente"]
    staff["Recepção / admin"]
    kong["Kong<br/>/api/billing/**"]
    mq{{"RabbitMQ"}}
    os["OS Service<br/>(orquestrador)"]
    mail["Mailpit / SMTP"]
    mp["Mercado Pago"]

    subgraph billing["Billing Service"]
        app["Spring Boot"]
        db[("PostgreSQL<br/>budgets, payments,<br/>outbox_messages,<br/>processed_messages")]
    end

    customer -->|"aprova, recusa, paga"| kong
    staff -->|"consulta"| kong
    kong --> app
    os -->|"comandos billing.*<br/>(fila billing.commands)"| mq --> app
    app -->|"respostas billing.*<br/>(saga.events)"| mq --> os
    app --> db
    app -->|"orçamento e link de pagamento"| mail
    app <-->|"preferência, consulta, estorno<br/>+ webhook assinado"| mp
```

O serviço não chama nenhum outro serviço e não lê nenhum outro banco. Tudo o que ele precisa saber sobre a OS chega no comando `billing.budget.generate`: cliente, e-mail, placa e itens **já precificados** pelo OS Service. O Billing só confere se o total bate com a soma dos itens.

## Componentes

```mermaid
flowchart LR
    kong["Kong"]
    mq{{"RabbitMQ"}}
    db[("PostgreSQL")]
    mail["SMTP"]
    gw["Gateway de pagamento"]

    subgraph app["Billing Service"]
        sec["Segurança<br/>JwtAuthenticationFilter,<br/>TokenVerifier"]
        ctrl["Controllers<br/>BudgetController,<br/>PaymentController,<br/>PaymentWebhookController"]
        listener["SagaCommandListener<br/>(idempotente)"]
        handler["SagaCommandHandler"]
        budget["BudgetServiceImpl"]
        payment["PaymentServiceImpl"]
        port["PaymentGateway (porta)<br/>SimulatedPaymentGateway<br/>MercadoPagoPaymentGateway"]
        pub["OutboxSagaEventPublisher"]
        relay["OutboxRelay"]
        notify["BillingNotificationListener"]
        repo["Adapters JPA"]
    end

    kong --> sec --> ctrl
    ctrl --> budget
    ctrl --> payment
    mq -->|"billing.commands"| listener --> handler
    handler --> budget
    handler --> payment
    payment --> port --> gw
    budget --> pub
    payment --> pub
    pub --> repo
    relay -->|"lê outbox"| repo
    relay -->|"saga.events"| mq
    budget -.->|"BudgetSentEvent"| notify
    payment -.->|"PaymentCreatedEvent"| notify
    notify --> mail
    repo --> db
```

| Componente | Responsabilidade |
|---|---|
| `SagaCommandListener` + `SagaCommandHandler` | Consome `billing.commands`, descarta reentregas (`processed_messages`) e despacha para o caso de uso |
| `BudgetServiceImpl` | Cria o orçamento, gera o token de aprovação de uso único, registra aprovação, recusa e cancelamento |
| `PaymentServiceImpl` | Cria a cobrança, trata o retorno do gateway (webhook ou simulação), estorna e cancela |
| `PaymentGateway` | Porta que isola o provedor. O adapter é escolhido por `PAYMENT_PROVIDER` (`simulated` ou `mercadopago`) |
| `OutboxSagaEventPublisher` + `OutboxRelay` | Grava a resposta da Saga na mesma transação da mudança de estado e publica em `saga.events` |
| `BillingNotificationListener` | Envia o e-mail do orçamento (links de aprovar e recusar) e o do link de pagamento, após o commit |
| Segurança | Valida o JWT emitido pelo OS Service ou pela Lambda; o perfil vem no claim `role`. O cliente só acessa a própria OS (e-mail do token = e-mail do orçamento) |

## Modelo de dados

```mermaid
erDiagram
    budgets ||--|{ budget_items : "itens (coleção)"
    budgets ||--o| payments : "cobrança"
    budgets {
        uuid id PK
        uuid service_order_id UK
        uuid saga_id
        string customer_email
        string license_plate
        decimal total_amount
        string status "SENT, APPROVED, REJECTED, CANCELED"
        string approval_token "uso único"
    }
    budget_items {
        uuid budget_id FK
        int position
        string item_type "LABOR ou PART"
        uuid reference_id
        int quantity
        decimal unit_price
        decimal total_price
    }
    payments {
        uuid id PK
        uuid budget_id FK
        uuid service_order_id
        decimal amount
        string provider "SIMULATED, MERCADO_PAGO"
        string provider_payment_id
        string checkout_url
        string status "PENDING, CONFIRMED, FAILED, REFUNDED, CANCELED"
    }
    outbox_messages {
        uuid id PK
        string message_type
        string routing_key
        text payload
        timestamp created_at
        timestamp published_at "nulo = pendente"
    }
    processed_messages {
        uuid message_id PK
        string message_type
        timestamp processed_at
    }
```

## Ciclo de vida

```mermaid
stateDiagram-v2
    state "Orçamento" as B {
        [*] --> SENT: billing.budget.generate
        SENT --> APPROVED: cliente aprova
        SENT --> REJECTED: cliente recusa
        SENT --> CANCELED: billing.budget.cancel
        APPROVED --> CANCELED: billing.budget.cancel
    }
    state "Pagamento" as P {
        [*] --> PENDING: billing.payment.create
        PENDING --> CONFIRMED: gateway aprova
        PENDING --> FAILED: gateway recusa
        PENDING --> CANCELED: billing.budget.cancel
        CONFIRMED --> REFUNDED: billing.payment.refund
    }
```

## Participação na Saga

| Comando recebido | Ação | Resposta |
|---|---|---|
| `billing.budget.generate` | Valida os itens, cria o orçamento `SENT` e envia o e-mail | `billing.budget.sent` ou `billing.budget.failed` |
| — (cliente, via link ou API) | Aprova ou recusa | `billing.budget.approved` / `billing.budget.rejected` |
| `billing.payment.create` | Cria a cobrança no gateway e envia o link | `billing.payment.created` |
| — (webhook ou simulação) | Resultado do gateway | `billing.payment.confirmed` / `billing.payment.failed` |
| `billing.payment.refund` | Estorna o pagamento confirmado (compensação) | `billing.payment.refunded` |
| `billing.budget.cancel` | Cancela o orçamento e a cobrança ainda não paga (compensação) | — |

```mermaid
sequenceDiagram
    autonumber
    participant OS as OS Service
    participant BI as Billing Service
    participant GW as Gateway
    actor C as Cliente

    OS->>BI: billing.budget.generate
    BI->>C: e-mail com aprovar / recusar
    BI-->>OS: billing.budget.sent
    C->>BI: GET /api/billing/budgets/approval?token=...&approved=true
    BI-->>OS: billing.budget.approved
    OS->>BI: billing.payment.create
    BI->>GW: cria cobrança
    BI->>C: e-mail com link de pagamento
    BI-->>OS: billing.payment.created
    GW->>BI: webhook (ou POST .../simulate)
    BI-->>OS: billing.payment.confirmed
```

## Decisões

- **PostgreSQL:** orçamento e pagamento são valores monetários com transições que precisam ser atômicas junto com a outbox. Um banco relacional com transação ACID é a escolha natural.
- **Porta de pagamento:** o resto do serviço (ciclo do pagamento, webhook, respostas da Saga, estorno) não conhece o provedor. O adapter `MercadoPagoPaymentGateway` implementa o Checkout Pro: cria a preferência, valida a assinatura do webhook (HMAC-SHA256, como o SDK oficial), consulta o pagamento na API e estorna. O `SimulatedPaymentGateway` continua disponível para desenvolvimento e testes de carga.
- **Token de aprovação de uso único:** o cliente aprova pelo link do e-mail sem precisar de login. O token é invalidado na primeira resposta.
- **Idempotência:** comandos reentregues são reconhecidos pelo `messageId` (`processed_messages`). O contrato do gateway exige idempotência: a cobrança leva o id do pagamento como referência, e o estorno, uma chave de idempotência.
- **Compensações seguras:** cancelar um orçamento já cancelado ou recusado não faz nada, e estornar exige pagamento confirmado.

## Observabilidade

Logs estruturados em JSON (perfil `prod`), `correlationId` lido das mensagens e propagado no MDC e no New Relic, e eventos `MechanicShopSagaFailure` e `MechanicShopIntegrationFailure` para falhas de passo e de integração.
