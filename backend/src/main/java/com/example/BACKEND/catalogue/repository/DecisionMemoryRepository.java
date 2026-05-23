package com.example.BACKEND.catalogue.repository;

import com.example.BACKEND.catalogue.entity.DecisionMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DecisionMemoryRepository extends JpaRepository<DecisionMemoryEntity, Long> {

    /** Count COMPLETED actions for a given client + badge combination. */
    @Query("SELECT COUNT(d) FROM DecisionMemoryEntity d " +
           "WHERE d.clientId = :clientId AND d.badge = :badge AND d.action = 'COMPLETED'")
    long countCompleted(@Param("clientId") String clientId, @Param("badge") String badge);

    /** Count DECLINED actions for a given client + badge combination. */
    @Query("SELECT COUNT(d) FROM DecisionMemoryEntity d " +
           "WHERE d.clientId = :clientId AND d.badge = :badge AND d.action = 'DECLINED'")
    long countDeclined(@Param("clientId") String clientId, @Param("badge") String badge);

    /** Total decisions for a client + badge (for acceptance rate calculation). */
    @Query("SELECT COUNT(d) FROM DecisionMemoryEntity d " +
           "WHERE d.clientId = :clientId AND d.badge = :badge")
    long countTotal(@Param("clientId") String clientId, @Param("badge") String badge);

    @Query("SELECT COUNT(d) FROM DecisionMemoryEntity d " +
           "WHERE d.clientId = :clientId AND d.agentName = :agentName")
    long countTotalByAgent(@Param("clientId") String clientId, @Param("agentName") String agentName);

    @Query("SELECT COUNT(d) FROM DecisionMemoryEntity d " +
           "WHERE d.clientId = :clientId AND d.agentName = :agentName AND d.action = 'COMPLETED'")
    long countCompletedByAgent(@Param("clientId") String clientId, @Param("agentName") String agentName);
}
