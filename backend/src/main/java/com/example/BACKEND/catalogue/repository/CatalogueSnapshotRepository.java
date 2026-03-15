package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.CatalogueSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CatalogueSnapshotRepository extends JpaRepository<CatalogueSnapshotEntity, Long> {

    Optional<CatalogueSnapshotEntity> findByClientId(String clientId);

    void deleteByClientId(String clientId);

    @Query("select s.clientId from CatalogueSnapshotEntity s order by s.clientId asc")
    List<String> findAllClientIds();
}
