package com.smarsh.discoveryhub.holdretention.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RetentionPolicyRepository extends JpaRepository<RetentionPolicy, com.smarsh.discoveryhub.events.MessageType> {
}
