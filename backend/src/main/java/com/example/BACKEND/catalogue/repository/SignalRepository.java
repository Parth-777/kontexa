package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.SignalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface SignalRepository extends JpaRepository<SignalEntity, UUID> {

    /** Recent signals for a tenant, newest first. */
    List<SignalEntity> findByClientIdOrderByDetectedAtDesc(String clientId);

    /**
     * Deduplication check — has the same signal type on the same column
     * already fired within the deduplication window?
     */
    @Query("""
            SELECT COUNT(s) FROM SignalEntity s
            WHERE s.clientId   = :clientId
              AND s.tableName  = :tableName
              AND s.signalType = :signalType
              AND (:columnName IS NULL OR s.columnName = :columnName)
              AND s.detectedAt >= :since
            """)
    long countRecentDuplicates(
            @Param("clientId")   String clientId,
            @Param("tableName")  String tableName,
            @Param("signalType") String signalType,
            @Param("columnName") String columnName,
            @Param("since")      LocalDateTime since);

    /**
     * Retrieves the most recent stored signal for a table+column+type combination.
     * Used as the baseline for the next comparison run.
     */
    @Query("""
            SELECT s FROM SignalEntity s
            WHERE s.clientId   = :clientId
              AND s.tableName  = :tableName
              AND s.signalType = :signalType
              AND (:columnName IS NULL OR s.columnName = :columnName)
            ORDER BY s.detectedAt DESC
            LIMIT 1
            """)
    List<SignalEntity> findLatestSignal(
            @Param("clientId")   String clientId,
            @Param("tableName")  String tableName,
            @Param("signalType") String signalType,
            @Param("columnName") String columnName);
}
