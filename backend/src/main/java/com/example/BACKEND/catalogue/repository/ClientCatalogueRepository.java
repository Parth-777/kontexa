package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClientCatalogueRepository extends JpaRepository<ClientCatalogueEntity, Long> {

    List<ClientCatalogueEntity> findByClientId(String clientId);
    List<ClientCatalogueEntity> findByClientIdIgnoreCase(String clientId);
    List<ClientCatalogueEntity> findBySchemaNameIgnoreCase(String schemaName);
    List<ClientCatalogueEntity> findByTenantId(UUID tenantId);

    Optional<ClientCatalogueEntity> findByClientIdAndStatus(String clientId, String status);
    Optional<ClientCatalogueEntity> findByTenantIdAndStatus(UUID tenantId, String status);
}
