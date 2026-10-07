package com.rentradar.backend.repository;

import com.rentradar.backend.domain.AuditLogEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AuditLogEntryRepository extends MongoRepository<AuditLogEntry, String> {

    List<AuditLogEntry> findByCorrelationId(String correlationId);

    Page<AuditLogEntry> findByActorIdOrderByOccurredAtDesc(String actorId, Pageable pageable);
}