package com.rentradar.backend.repository;

import com.rentradar.backend.domain.FraudRiskAlert;
import com.rentradar.backend.domain.type.RiskLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FraudRiskAlertRepository extends MongoRepository<FraudRiskAlert, String> {

    List<FraudRiskAlert> findByPropertyIdOrderByTriggeredAtDesc(String propertyId);

    Page<FraudRiskAlert> findByRiskLevelOrderByTriggeredAtDesc(RiskLevel level, Pageable pageable);
}