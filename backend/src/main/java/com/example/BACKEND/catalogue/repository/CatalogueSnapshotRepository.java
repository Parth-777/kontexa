package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.CatalogueSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CatalogueSnapshotRepository extends JpaRepository<CatalogueSnapshotEntity, Long> {

    Optional<CatalogueSnapshotEntity> findByClientId(String clientId);

    void deleteByClientId(String clientId);
}
