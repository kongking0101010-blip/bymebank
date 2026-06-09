package com.khmerbank.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated thread pool for fire-and-forget background work — mainly sending
 * OTP / notification emails off the request thread so login feels instant.
 *
 * <p>Without this, {@code @Async} methods run on Spring's default
 * {@code SimpleAsyncTaskExecutor}, which spawns an unbounded new thread per
 * call. A bounded pool keeps memory predictable on the free-tier VPS and lets
 * a brief burst of sign-ins queue instead of exhausting threads.
 */
@Configuration
public class AsyncConfig {

    /** Used by {@code @Async("mailExecutor")} methods in EmailService. */
    @Bean(name = "mailExecutor")
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("mail-");
        // If the queue ever fills, run on the caller thread rather than drop
        // the email — slower, but the user still gets their code.
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(20);
        ex.initialize();
        return ex;
    }
}
