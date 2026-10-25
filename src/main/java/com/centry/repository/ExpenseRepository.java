package com.centry.repository;

import com.centry.entity.ExpenseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface ExpenseRepository extends JpaRepository<ExpenseEntity, Long> {
    List<ExpenseEntity> findByCategoryIgnoreCase(String category);

    @Query("SELECT e.category, SUM(e.amount) FROM ExpenseEntity e GROUP BY e.category")
    List<Object[]> sumAmountByCategory();
}
