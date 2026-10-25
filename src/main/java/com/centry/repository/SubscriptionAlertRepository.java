package com.centry.repository;

import com.centry.entity.SubscriptionAlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubscriptionAlertRepository extends JpaRepository<SubscriptionAlertEntity, Long> {
    List<SubscriptionAlertEntity> findTop50ByOrderByTimestampDesc();
}
