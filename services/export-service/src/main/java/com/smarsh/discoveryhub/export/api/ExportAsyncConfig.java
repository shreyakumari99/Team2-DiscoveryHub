package com.smarsh.discoveryhub.export.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Where export jobs actually run (FR-6.2).
 *
 * <p>A virtual thread per job: export work is dominated by waiting on
 * archive-service and S3, which is exactly what virtual threads are for, and
 * it means a hundred concurrent exports cost a hundred cheap threads rather
 * than exhausting a pool.
 *
 * <p>Injecting this as an {@link Executor} rather than calling
 * {@code Thread.ofVirtual()} inside the service also makes the job lifecycle
 * testable: a test supplies an executor it controls and drives the worker
 * deliberately, instead of racing a thread it cannot see.
 */
@Configuration
public class ExportAsyncConfig {

    public static final String EXPORT_EXECUTOR = "exportExecutor";

    @Bean(name = EXPORT_EXECUTOR, destroyMethod = "close")
    public Executor exportExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
