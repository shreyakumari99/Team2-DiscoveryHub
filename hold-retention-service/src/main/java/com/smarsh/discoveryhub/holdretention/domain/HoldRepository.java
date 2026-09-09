package com.smarsh.discoveryhub.holdretention.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HoldRepository extends JpaRepository<HoldEntity, String> {
    List<HoldEntity> findByCaseId(String caseId);

    List<HoldEntity> findByCaseIdAndActiveTrue(String caseId);

    /**
     * Eagerly load the {@code custodians} {@code @ElementCollection} within the
     * transaction. Required because the service runs with
     * {@code spring.jpa.open-in-view=false}, so lazy initialization during JSON
     * serialization would throw {@code LazyInitializationException}.
     */
    @Query("SELECT DISTINCT h FROM HoldEntity h LEFT JOIN FETCH h.custodians")
    List<HoldEntity> findAllWithCustodians();

    @Query("SELECT h FROM HoldEntity h LEFT JOIN FETCH h.custodians WHERE h.id = :id")
    Optional<HoldEntity> findWithCustodiansById(@Param("id") String id);

    @Query("SELECT DISTINCT h FROM HoldEntity h LEFT JOIN FETCH h.custodians WHERE h.caseId = :caseId")
    List<HoldEntity> findByCaseIdWithCustodians(@Param("caseId") String caseId);
}
