package com.sutra.extraction.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Quotation extraction runs on this pool so uploads return immediately. The pool size is
 * {@code sutra.extraction.concurrency} (default 1): free AI tiers allow only a few requests per minute, so
 * quotations are read one after another instead of all at once.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "extractionExecutor")
    Executor extractionExecutor(ExtractionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.concurrency());
        executor.setMaxPoolSize(properties.concurrency());
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("extract-");
        executor.initialize();
        return executor;
    }
}
