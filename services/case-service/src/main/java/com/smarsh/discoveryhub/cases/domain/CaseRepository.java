package com.smarsh.discoveryhub.cases.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CaseRepository extends JpaRepository<CaseEntity, String> {

    /**
     * Eagerly load the {@code custodianIds} {@code @ElementCollection} within
     * the transaction. Required because the service runs with
     * {@code spring.jpa.open-in-view=false} (deliberate, to keep tx boundaries
     * explicit), so lazy initialization during JSON serialization would throw
     * {@code LazyInitializationException}. {@code DISTINCT} dedups the rows
     * produced by the collection join.
     */
    @Query("SELECT DISTINCT c FROM CaseEntity c LEFT JOIN FETCH c.custodianIds")
    List<CaseEntity> findAllWithCustodians();

    @Query("SELECT c FROM CaseEntity c LEFT JOIN FETCH c.custodianIds WHERE c.id = :id")
    Optional<CaseEntity> findWithCustodiansById(@Param("id") String id);
}
