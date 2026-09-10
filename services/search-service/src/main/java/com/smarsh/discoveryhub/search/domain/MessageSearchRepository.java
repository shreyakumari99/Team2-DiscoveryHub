package com.smarsh.discoveryhub.search.domain;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/** Elasticsearch repository for {@link MessageDocument}. */
@Repository
public interface MessageSearchRepository extends ElasticsearchRepository<MessageDocument, String> {
}
