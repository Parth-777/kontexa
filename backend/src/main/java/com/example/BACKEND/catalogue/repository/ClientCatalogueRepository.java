package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientCatalogueRepository extends JpaRepository<ClientCatalogueEntity, Long> {

    List<ClientCatalogueEntity> findByClientId(String clientId);

    Optional<ClientCatalogueEntity> findByClientIdAndStatus(String clientId, String status);
}
