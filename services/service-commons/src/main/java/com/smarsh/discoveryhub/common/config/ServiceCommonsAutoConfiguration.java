package com.smarsh.discoveryhub.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import com.smarsh.discoveryhub.common.audit.KafkaAuditTrail;
import com.smarsh.discoveryhub.common.http.ServiceClients;
import com.smarsh.discoveryhub.common.web.ActorHeaderFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;

/**
 * Wires the cross-cutting concerns every backend service needs, so a service
 * gets them by declaring the {@code service-commons} dependency and nothing
 * else. Both beans are {@code @ConditionalOnMissingBean}, so a service (or a
 * test) can still substitute its own implementation.
 */
@AutoConfiguration
public class ServiceCommonsAutoConfiguration {

    /**
     * @param serviceName identifies the emitting service in every audit entry;
     *                    taken from {@code spring.application.name}
     */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(AuditTrail.class)
    public AuditTrail auditTrail(KafkaTemplate<String, Object> kafkaTemplate,
                                 ObjectMapper objectMapper,
                                 @Value("${spring.application.name:unknown-service}") String serviceName) {
        return new KafkaAuditTrail(kafkaTemplate, objectMapper, serviceName);
    }

    /**
     * Binds the {@code X-Actor} header for the duration of each request.
     * Ordered highest so the actor is available to anything that audits,
     * including other filters.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(ActorHeaderFilter.class)
    public FilterRegistrationBean<ActorHeaderFilter> actorHeaderFilter() {
        FilterRegistrationBean<ActorHeaderFilter> registration =
                new FilterRegistrationBean<>(new ActorHeaderFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(ServiceClients.class)
    public ServiceClients serviceClients(
            @Value("${discoveryhub.http.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${discoveryhub.http.read-timeout:PT10S}") Duration readTimeout) {
        return new ServiceClients(connectTimeout, readTimeout);
    }
}
