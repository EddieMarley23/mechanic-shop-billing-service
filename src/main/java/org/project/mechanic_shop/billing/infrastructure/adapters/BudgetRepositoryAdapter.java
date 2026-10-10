package org.project.mechanic_shop.billing.infrastructure.adapters;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.project.mechanic_shop.billing.application.ports.BudgetRepositoryPort;
import org.project.mechanic_shop.billing.domain.entities.budget.Budget;
import org.project.mechanic_shop.billing.infrastructure.jpa.mappers.BudgetJpaMapper;
import org.project.mechanic_shop.billing.infrastructure.jpa.repositories.BudgetJpaRepository;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class BudgetRepositoryAdapter implements BudgetRepositoryPort {

	private final BudgetJpaRepository jpaRepository;
	private final BudgetJpaMapper mapper;

	@Override
	public Budget save(Budget budget) {
		return mapper.toDomain(jpaRepository.save(mapper.toJpa(budget)));
	}

	@Override
	public Optional<Budget> findById(UUID id) {
		return jpaRepository.findById(id).map(mapper::toDomain);
	}

	@Override
	public Optional<Budget> findByServiceOrderId(UUID serviceOrderId) {
		return jpaRepository.findByServiceOrderId(serviceOrderId).map(mapper::toDomain);
	}

	@Override
	public Optional<Budget> findByApprovalToken(String approvalToken) {
		return jpaRepository.findByApprovalToken(approvalToken).map(mapper::toDomain);
	}
}
