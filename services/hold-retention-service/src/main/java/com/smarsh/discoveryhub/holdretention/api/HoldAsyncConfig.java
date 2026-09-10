package com.smarsh.discoveryhub.holdretention.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Where hold scope resolution runs (FR-4.3 "holds must propagate
 * asynchronously — placing a hold on a large scope must not block the user").
 *
 * <p>Resolution is almost entirely time spent waiting on search-service, so a
 * virtual thread per hold is both cheap and appropriate.
 *
 * <p>Injected rather than created inline so tests can drive resolution
 * deterministically instead of racing a thread they cannot observe.
 */
@Configuration
public class HoldAsyncConfig {

    public static final String SCOPE_EXECUTOR = "holdScopeExecutor";

    @Bean(name = SCOPE_EXECUTOR, destroyMethod = "close")
    public Executor holdScopeExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
