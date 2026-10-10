package org.project.mechanic_shop.billing.application.services.impl;

import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.billing.application.ports.BudgetRepositoryPort;
import org.project.mechanic_shop.billing.application.ports.SagaEventPublisher;
import org.project.mechanic_shop.billing.application.saga.GenerateBudgetPayload;
import org.project.mechanic_shop.billing.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.billing.application.services.BudgetService;
import org.project.mechanic_shop.billing.application.services.Requester;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.project.mechanic_shop.billing.domain.entities.budget.BudgetItem;
import org.project.mechanic_shop.billing.domain.enums.BudgetStatusEnum;
import org.project.mechanic_shop.billing.domain.events.BudgetSentEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetServiceImpl implements BudgetService {

	private final BudgetRepositoryPort budgetRepository;
	private final SagaEventPublisher sagaEventPublisher;
	private final ApplicationEventPublisher eventPublisher;
	private final Clock clock;

	private static final String BUDGET_NOT_FOUND_MSG = "Budget not found for Service Order: ";

	@Override
	@Transactional
	public Budget generate(UUID sagaId, UUID serviceOrderId, GenerateBudgetPayload payload) {
		Optional<Budget> existing = budgetRepository.findByServiceOrderId(serviceOrderId);
		if (existing.isPresent()) {
			log.info("Budget already generated for Service Order {}. Ignoring duplicated command.", serviceOrderId);
			return existing.get();
		}

		Budget budget = new Budget();
		budget.setId(UUID.randomUUID());
		budget.setServiceOrderId(serviceOrderId);
		budget.setSagaId(sagaId);
		budget.setCustomerName(payload.customerName());
		budget.setCustomerEmail(payload.customerEmail());
		budget.setLicensePlate(payload.licensePlate());
		budget.setTotalAmount(payload.totalAmount());
		budget.setEstimatedCompletionDays(payload.estimatedCompletionDays());
		budget.setStatus(BudgetStatusEnum.SENT);
		budget.setApprovalToken(UUID.randomUUID().toString());
		budget.setCreatedAt(Instant.now(clock));
		budget.setItems(
			payload
				.items()
				.stream()
				.map(i ->
					new BudgetItem(
						i.type(),
						i.referenceId(),
						i.description(),
						i.quantity(),
						i.unitPrice(),
						i.totalPrice()
					)
				)
				.toList()
		);

		Budget saved = budgetRepository.save(budget);

		sagaEventPublisher.publish(
			sagaId,
			serviceOrderId,
			SagaMessageTypes.BUDGET_SENT,
			Map.of("budgetId", saved.getId(), "totalAmount", saved.getTotalAmount())
		);
		eventPublisher.publishEvent(new BudgetSentEvent(saved, saved.getApprovalToken()));

		log.info("Budget {} generated for Service Order {}. Total: R$ {}", saved.getId(), serviceOrderId, saved.getTotalAmount());
		return saved;
	}

	@Override
	@Transactional
	public Budget respond(UUID serviceOrderId, boolean approved, Requester requester) {
		log.info("Processing budget response for OS: {}. Approved: {}", serviceOrderId, approved);

		Budget budget = budgetRepository
			.findByServiceOrderId(serviceOrderId)
			.orElseThrow(() -> new EntityNotFoundException(BUDGET_NOT_FOUND_MSG + serviceOrderId));

		assertCustomerOwnsBudget(budget, requester);

		return applyResponse(budget, approved);
	}

	@Override
	@Transactional
	public Budget respondByToken(String token, boolean approved) {
		log.info("Processing budget response via email token. Approved: {}", approved);

		Budget budget = budgetRepository
			.findByApprovalToken(token)
			.orElseThrow(() -> new EntityNotFoundException("Token de aprovação inválido ou já utilizado."));

		return applyResponse(budget, approved);
	}

	@Override
	@Transactional
	public void cancel(UUID serviceOrderId, String reason) {
		budgetRepository
			.findByServiceOrderId(serviceOrderId)
			.filter(Budget::cancel)
			.ifPresent(budget -> {
				budgetRepository.save(budget);
				log.info("Budget {} canceled by Saga compensation. Reason: {}", budget.getId(), reason);
			});
	}

	@Override
	@Transactional(readOnly = true)
	public Budget findByServiceOrderId(UUID serviceOrderId, Requester requester) {
		Budget budget = budgetRepository
			.findByServiceOrderId(serviceOrderId)
			.orElseThrow(() -> new EntityNotFoundException(BUDGET_NOT_FOUND_MSG + serviceOrderId));

		assertCustomerOwnsBudget(budget, requester);

		return budget;
	}

	private Budget applyResponse(Budget budget, boolean approved) {
		Instant now = Instant.now(clock);

		if (approved) {
			budget.approve(now);
		} else {
			budget.reject(now);
		}

		Budget saved = budgetRepository.save(budget);

		sagaEventPublisher.publish(
			saved.getSagaId(),
			saved.getServiceOrderId(),
			approved ? SagaMessageTypes.BUDGET_APPROVED : SagaMessageTypes.BUDGET_REJECTED,
			Map.of("budgetId", saved.getId())
		);

		return saved;
	}

	private void assertCustomerOwnsBudget(Budget budget, Requester requester) {
		if (requester == null || !requester.isCustomer()) return;

		if (!budget.belongsTo(requester.email())) {
			throw new AccessDeniedException("You can only access your own budgets.");
		}
	}
}
