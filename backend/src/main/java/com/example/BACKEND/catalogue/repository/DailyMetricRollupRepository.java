package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.DailyMetricRollupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface DailyMetricRollupRepository extends JpaRepository<DailyMetricRollupEntity, UUID> {

    List<DailyMetricRollupEntity> findByClientIdAndTableNameAndMetricNameAndDimensionKeyIsNullOrderByMetricDateAsc(
            String clientId, String tableName, String metricName);

    @Query("SELECT MAX(r.builtAt) FROM DailyMetricRollupEntity r " +
           "WHERE r.clientId = :clientId AND r.tableName = :tableName")
    LocalDateTime findLatestBuiltAt(@Param("clientId") String clientId,
                                    @Param("tableName") String tableName);

    long countByClientIdAndTableName(String clientId, String tableName);

    @Modifying
    @Query("DELETE FROM DailyMetricRollupEntity r " +
           "WHERE r.clientId = :clientId AND r.tableName = :tableName")
    void deleteByClientIdAndTableName(@Param("clientId") String clientId,
                                      @Param("tableName") String tableName);
}
