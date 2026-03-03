package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CatalogueTableRepository extends JpaRepository<CatalogueTableEntity, Long> {

    List<CatalogueTableEntity> findByCatalogueId(Long catalogueId);
}
