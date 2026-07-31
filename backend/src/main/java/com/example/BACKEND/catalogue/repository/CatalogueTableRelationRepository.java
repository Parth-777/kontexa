package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.CatalogueTableRelationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CatalogueTableRelationRepository extends JpaRepository<CatalogueTableRelationEntity, Long> {

    List<CatalogueTableRelationEntity> findByClientId(String clientId);

    List<CatalogueTableRelationEntity> findByTenantId(UUID tenantId);

    List<CatalogueTableRelationEntity> findByClientIdAndFactTable(String clientId, String factTable);

    List<CatalogueTableRelationEntity> findByTenantIdAndFactTable(UUID tenantId, String factTable);

    void deleteByClientId(String clientId);

    void deleteByTenantId(UUID tenantId);
}
