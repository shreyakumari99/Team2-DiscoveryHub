package com.smarsh.discoveryhub.search.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** JPA repository for {@link SavedSearch} (stored in the embedded H2 DB). */
@Repository
public interface SavedSearchRepository extends JpaRepository<SavedSearch, String> {
    List<SavedSearch> findByCaseId(String caseId);
}
