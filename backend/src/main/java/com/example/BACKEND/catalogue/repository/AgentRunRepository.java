package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.AgentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, UUID> {
}
