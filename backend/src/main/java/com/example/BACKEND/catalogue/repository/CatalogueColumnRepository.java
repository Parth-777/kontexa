package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CatalogueColumnRepository extends JpaRepository<CatalogueColumnEntity, Long> {

    List<CatalogueColumnEntity> findByCatalogueTableId(Long tableId);
}
