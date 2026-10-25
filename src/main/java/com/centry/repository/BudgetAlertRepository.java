package com.centry.repository;

import com.centry.entity.BudgetAlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BudgetAlertRepository extends JpaRepository<BudgetAlertEntity, Long> {
    List<BudgetAlertEntity> findTop50ByOrderByTimestampDesc();
}
