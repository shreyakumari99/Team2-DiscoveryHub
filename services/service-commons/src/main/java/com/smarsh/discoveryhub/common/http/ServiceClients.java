package com.smarsh.discoveryhub.common.http;

import com.smarsh.discoveryhub.common.audit.CurrentActor;
import com.smarsh.discoveryhub.common.web.ActorHeaderFilter;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builds {@link RestClient}s for calling other DiscoveryHub services (NFR-2).
 *
 * <p>The reason this exists rather than each client calling
 * {@code RestClient.builder().baseUrl(url).build()} is the default: the JDK
 * HTTP client has <em>no read timeout</em>. A downstream service that accepts
 * the connection but never answers would otherwise hang the calling thread
 * forever — a disposition run or an export job would stall indefinitely
 * instead of failing and being retried, which is precisely the cascading
 * failure NFR-2 forbids. Every inter-service client is therefore created here,
 * with bounded connect and read timeouts.
 */
public class ServiceClients {

    private final Duration connectTimeout;
    private final Duration readTimeout;

    public ServiceClients(Duration connectTimeout, Duration readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    /** A client bound to {@code baseUrl} with the platform's standard timeouts. */
    public RestClient forService(String baseUrl) {
        return builderFor(baseUrl).build();
    }

    /**
     * As {@link #forService(String)}, but returns the builder so a caller can
     * add its own interceptors or default headers.
     */
    public RestClient.Builder builderFor(String baseUrl) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .requestInterceptor(forwardActorHeader());
    }

    /**
     * Carries the acting investigator across a service hop, so an action that
     * fans out is attributed to one person end to end instead of to whichever
     * service made the onward call (FR-7.2).
     */
    private static ClientHttpRequestInterceptor forwardActorHeader() {
        return (request, body, execution) -> {
            CurrentActor.get().ifPresent(actor ->
                    request.getHeaders().set(ActorHeaderFilter.ACTOR_HEADER, actor));
            return execution.execute(request, body);
        };
    }

    /**
     * A client for calls expected to be slow (e.g. paging through a
     * corpus-wide search to resolve a hold scope), where the standard read
     * timeout would be too aggressive.
     */
    public RestClient forSlowService(String baseUrl, Duration readTimeoutOverride) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeoutOverride);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .requestInterceptor(forwardActorHeader())
                .build();
    }
}
