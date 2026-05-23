package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.AgentReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentReportRepository extends JpaRepository<AgentReportEntity, UUID> {

    List<AgentReportEntity> findByClientIdOrderByGeneratedAtDesc(String clientId);

    List<AgentReportEntity> findByClientIdAndPeriodTypeOrderByGeneratedAtDesc(
            String clientId, String periodType);
}
