package com.smarsh.discoveryhub.archive.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * Kafka wiring for archive-service: it both consumes (message-ingested,
 * hold-events) and produces (message-archived, audit-events).
 *
 * <p>Producers use {@link JsonSerializer} with a JavaTimeModule-aware mapper so
 * {@code java.time.Instant} fields serialize correctly, and emit type headers
 * (all event types live in the shared-contracts module on every classpath).
 * Consumers use {@link JsonDeserializer} with the same mapper and trust the
 * {@code com.smarsh.discoveryhub.*} packages, resolving the concrete event type
 * from the type header.
 */
@Configuration
public class KafkaConfig {

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    // ---- Producer -----------------------------------------------------------------
    @Bean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildProducerProperties();
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(props);
        factory.setValueSerializer(new JsonSerializer<>(objectMapper()));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // ---- Consumer -----------------------------------------------------------------
    @Bean
    public ConsumerFactory<String, Object> consumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties();
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put("spring.json.trusted.packages", "com.smarsh.discoveryhub.*");
        JsonDeserializer<Object> valueDeserializer = new JsonDeserializer<>(Object.class, objectMapper());
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), valueDeserializer);
    }

    /**
     * @param concurrency consumer threads per listener. Defaults to the topic
     *                    partition count (3), not Spring's default of 1.
     *                    <p>Archiving a message costs two MongoDB round trips
     *                    (the idempotency lookup and the write) plus an S3 PUT
     *                    when it has attachments, so a single thread is
     *                    latency-bound and leaves the partitions — which exist
     *                    precisely to be consumed in parallel — idle. Ordering
     *                    is unaffected: messages are keyed by
     *                    {@code sourceMessageId}, so all events for one message
     *                    still land on one partition and are handled by one
     *                    thread, which is what the dedup check relies on.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            @Value("${archive.consumer-concurrency:3}") int concurrency) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        return factory;
    }
}
