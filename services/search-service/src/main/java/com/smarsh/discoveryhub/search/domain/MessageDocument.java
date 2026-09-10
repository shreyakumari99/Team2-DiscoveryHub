package com.smarsh.discoveryhub.search.domain;

import com.smarsh.discoveryhub.events.MessageType;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;

import java.time.Instant;
import java.util.List;

/**
 * Elasticsearch document representing a searchable archived message.
 *
 * <p>{@code subject}, {@code body} and {@code participants} are indexed for
 * full-text search (FR-3.1). The metadata fields ({@code type}, {@code sender},
 * {@code timestamp}, {@code hasAttachment}, {@code held}) back the filters
 * (FR-3.2). {@code held} is denormalized here so the "on-hold" filter and the
 * per-message hold badge in the UI work without a call to archive-service.
 *
 * <p>{@code participants} is a multi-field: analyzed text as the main field so
 * searching "alice" matches {@code alice.chen@smarsh.com} and so highlighting
 * returns fragments, plus a {@code .keyword} sub-field for the exact-match
 * custodian filter. As a keyword-only field it could do neither.
 */
@Document(indexName = "discoveryhub-messages")
public record MessageDocument(
        @Id @Field(type = FieldType.Keyword) String id,
        @Field(type = FieldType.Keyword) String sourceMessageId,
        @Field(type = FieldType.Keyword) MessageType type,
        @Field(type = FieldType.Text) String subject,
        @Field(type = FieldType.Text) String body,
        @Field(type = FieldType.Date) Instant timestamp,
        @Field(type = FieldType.Keyword) String sender,
        @MultiField(
                mainField = @Field(type = FieldType.Text),
                otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword))
        List<String> participants,
        @Field(type = FieldType.Keyword) String threadId,
        @Field(type = FieldType.Boolean) boolean hasAttachment,
        @Field(type = FieldType.Boolean) boolean held,
        @Field(type = FieldType.Date) Instant archivedAt
) {
}
