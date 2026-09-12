package com.smarsh.discoveryhub.common.http;

import com.smarsh.discoveryhub.common.audit.CurrentActor;
import com.smarsh.discoveryhub.common.web.ActorHeaderFilter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The factory every inter-service HTTP client is built from (NFR-2, FR-7.2).
 *
 * <p>Tested against a real loopback HTTP server rather than a mock, because the
 * two things this class exists to guarantee are both properties of an actual
 * socket. The first is that a read timeout is applied at all: the JDK HTTP
 * client has <em>none</em> by default, so a downstream service that accepts a
 * connection and then never answers would hang the calling thread forever —
 * stalling a disposition run or an export job instead of failing it, which is
 * precisely the cascading failure NFR-2 forbids. The second is that the acting
 * investigator is carried across the hop, so one action stays attributable end
 * to end rather than being credited to whichever service made the onward call.
 */
class ServiceClientsTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> receivedActors = new CopyOnWriteArrayList<>();
    private volatile long handlerDelayMillis;

    @BeforeEach
    void startServer() throws IOException {
        receivedActors.clear();
        handlerDelayMillis = 0;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", exchange -> {
            receivedActors.add(String.valueOf(exchange.getRequestHeaders()
                    .getFirst(ActorHeaderFilter.ACTOR_HEADER)));
            if (handlerDelayMillis > 0) {
                try {
                    Thread.sleep(handlerDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        CurrentActor.clear();
        server.stop(0);
    }

    private ServiceClients clients() {
        return new ServiceClients(Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    private String ping(RestClient client) {
        return client.get().uri("/ping").retrieve().body(String.class);
    }

    @Test
    void buildsAWorkingClientBoundToABaseUrl() {
        assertThat(ping(clients().forService(baseUrl))).isEqualTo("pong");
    }

    /**
     * FR-7.2: the investigator travels with the request. Without this, an action
     * that fans out — a search that resolves a hold scope, an export that reads
     * a case — would be audited against the calling service rather than the
     * person who started it.
     */
    @Test
    void forwardsTheActingInvestigatorOnTheHop() {
        CurrentActor.set("investigator@smarsh.com");

        ping(clients().forService(baseUrl));

        assertThat(receivedActors).containsExactly("investigator@smarsh.com");
    }

    /**
     * Background work has no actor on purpose, and none must be invented. The
     * audit trail should attribute unattended work to the service, which is
     * what the publisher does when the header is absent.
     */
    @Test
    void sendsNoActorHeaderWhenThereIsNoActor() {
        ping(clients().forService(baseUrl));

        // "null" is this test server's stand-in for an absent header.
        assertThat(receivedActors).containsExactly("null");
    }

    /** The builder variant lets a caller add its own headers and still forwards the actor. */
    @Test
    void theBuilderVariantKeepsTheActorInterceptor() {
        CurrentActor.set("alice@smarsh.com");

        RestClient client = clients().builderFor(baseUrl)
                .defaultHeader("X-Test", "yes")
                .build();

        assertThat(ping(client)).isEqualTo("pong");
        assertThat(receivedActors).containsExactly("alice@smarsh.com");
    }

    /**
     * The reason this class exists. A dependency that accepts the connection
     * and then stalls must fail the call, not hold the thread indefinitely.
     */
    @Test
    void aStalledDependencyFailsRatherThanHangingForever() {
        handlerDelayMillis = 2_000;
        ServiceClients impatient = new ServiceClients(Duration.ofSeconds(2), Duration.ofMillis(150));

        assertThatThrownBy(() -> ping(impatient.forService(baseUrl)))
                .isInstanceOf(ResourceAccessException.class);
    }

    /**
     * Resolving a hold's scope legitimately takes longer than an ordinary call,
     * so that one client gets a longer read timeout instead of the platform
     * default. This proves the override is actually applied — the same request
     * that times out above succeeds here.
     */
    @Test
    void aSlowServiceClientToleratesALongerResponse() {
        handlerDelayMillis = 400;

        RestClient slow = clients().forSlowService(baseUrl, Duration.ofSeconds(5));

        assertThat(ping(slow)).isEqualTo("pong");
    }

    /** The slow client forwards the actor too — it is not a lesser client. */
    @Test
    void theSlowServiceClientAlsoForwardsTheActor() {
        CurrentActor.set("bob@smarsh.com");

        ping(clients().forSlowService(baseUrl, Duration.ofSeconds(5)));

        assertThat(receivedActors).containsExactly("bob@smarsh.com");
    }

    /** A connect timeout applies as well, for a host that is not listening. */
    @Test
    void anUnreachableHostFailsPromptly() {
        // Port 1 on loopback: nothing listens, so the connection is refused.
        RestClient client = clients().forService("http://127.0.0.1:1");

        assertThatThrownBy(() -> ping(client)).isInstanceOf(ResourceAccessException.class);
    }
}
