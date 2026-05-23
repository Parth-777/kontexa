package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.InsightCardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface InsightCardRepository extends JpaRepository<InsightCardEntity, UUID> {

    /** All non-expired cards for a client, newest first. */
    List<InsightCardEntity> findByClientIdAndStatusNotOrderByGeneratedAtDesc(
            String clientId, String status);

    /** Cards by specific status (e.g. AWAITING_CONFIRMATION). */
    List<InsightCardEntity> findByClientIdAndStatusOrderByGeneratedAtDesc(
            String clientId, String status);

    /** Auto-expire cards whose TTL has passed and are still pending. */
    @Modifying
    @Transactional
    @Query("UPDATE InsightCardEntity c SET c.status = 'EXPIRED' " +
           "WHERE c.clientId = :clientId AND c.status = 'AWAITING_CONFIRMATION' " +
           "AND c.expiresAt < :now")
    int expireOldCards(@Param("clientId") String clientId,
                       @Param("now") LocalDateTime now);

    /**
     * Immediately retire ALL pending cards for a client when a fresh analysis
     * is triggered. Prevents card accumulation across repeated refreshes.
     * DECLINED and COMPLETED cards are kept for decision memory.
     */
    @Modifying
    @Transactional
    @Query("UPDATE InsightCardEntity c SET c.status = 'EXPIRED' " +
           "WHERE c.clientId = :clientId AND c.status = 'AWAITING_CONFIRMATION'")
    int retireAllPendingCards(@Param("clientId") String clientId);

    /** Mark a single card status change and set acted_at. */
    @Modifying
    @Transactional
    @Query("UPDATE InsightCardEntity c SET c.status = :status, c.actedAt = :now " +
           "WHERE c.id = :id AND c.clientId = :clientId")
    int updateStatus(@Param("id") UUID id,
                     @Param("clientId") String clientId,
                     @Param("status") String status,
                     @Param("now") LocalDateTime now);
}
