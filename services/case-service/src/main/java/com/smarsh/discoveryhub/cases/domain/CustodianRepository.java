package com.smarsh.discoveryhub.cases.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CustodianRepository extends JpaRepository<CustodianEntity, String> {
}
