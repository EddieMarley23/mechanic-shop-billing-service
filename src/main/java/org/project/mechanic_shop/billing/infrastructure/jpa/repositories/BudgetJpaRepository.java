package org.project.mechanic_shop.billing.infrastructure.jpa.repositories;

import java.util.Optional;
import java.util.UUID;
import org.project.mechanic_shop.billing.infrastructure.jpa.entities.BudgetJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetJpaRepository extends JpaRepository<BudgetJpaEntity, UUID> {
	Optional<BudgetJpaEntity> findByServiceOrderId(UUID serviceOrderId);
	Optional<BudgetJpaEntity> findByApprovalToken(String approvalToken);
}
