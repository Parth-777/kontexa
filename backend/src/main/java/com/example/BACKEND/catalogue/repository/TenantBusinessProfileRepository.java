package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.TenantBusinessProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantBusinessProfileRepository extends JpaRepository<TenantBusinessProfileEntity, Long> {
    Optional<TenantBusinessProfileEntity> findByClientId(String clientId);
}
