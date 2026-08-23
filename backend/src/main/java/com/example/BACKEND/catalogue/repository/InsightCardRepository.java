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

    List<InsightCardEntity> findByClientIdAndStatusNotOrderByGeneratedAtDesc(
            String clientId, String status);

    List<InsightCardEntity> findByTenantIdAndStatusNotOrderByGeneratedAtDesc(
            UUID tenantId, String status);

    List<InsightCardEntity> findByClientIdAndStatusOrderByGeneratedAtDesc(
            String clientId, String status);

    List<InsightCardEntity> findByTenantIdAndStatusOrderByGeneratedAtDesc(
            UUID tenantId, String status);

    @Modifying
    @Transactional
    @Query("UPDATE InsightCardEntity c SET c.status = 'EXPIRED' " +
           "WHERE c.clientId = :clientId AND c.status = 'AWAITING_CONFIRMATION' " +
           "AND c.expiresAt < :now")
    int expireOldCards(@Param("clientId") String clientId,
                       @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("UPDATE InsightCardEntity c SET c.status = 'EXPIRED' " +
           "WHERE c.tenantId = :tenantId AND c.status = 'AWAITING_CONFIRMATION' " +
           "AND c.expiresAt < :now")
    int expireOldCardsByTenantId(@Param("tenantId") UUID tenantId,
                                 @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("UPDATE InsightCardEntity c SET c.status = 'EXPIRED' " +
           "WHERE c.clientId = :clientId AND c.status = 'AWAITING_CONFIRMATION'")
    int retireAllPendingCards(@Param("clientId") String clientId);

    @Modifying
    @Transactional
    @Query("UPDATE InsightCardEntity c SET c.status = 'EXPIRED' " +
           "WHERE c.tenantId = :tenantId AND c.status = 'AWAITING_CONFIRMATION'")
    int retireAllPendingCardsByTenantId(@Param("tenantId") UUID tenantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE InsightCardEntity c SET c.status = :status, c.actedAt = :now " +
           "WHERE c.id = :id AND c.clientId = :clientId")
    int updateStatus(@Param("id") UUID id,
                     @Param("clientId") String clientId,
                     @Param("status") String status,
                     @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE InsightCardEntity c SET c.status = :status, c.actedAt = :now " +
           "WHERE c.id = :id AND c.tenantId = :tenantId")
    int updateStatusByTenantId(@Param("id") UUID id,
                               @Param("tenantId") UUID tenantId,
                               @Param("status") String status,
                               @Param("now") LocalDateTime now);
}
