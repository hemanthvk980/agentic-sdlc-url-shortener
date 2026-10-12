package io.github.hemanthvk980.shortener.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Click analytics are recorded off the redirect hot path on a bounded executor. When the queue is
 * full, events are dropped (DiscardPolicy) rather than slowing redirects or exhausting memory:
 * analytics are best-effort, redirects are the product.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    public static final String CLICK_EXECUTOR = "clickExecutor";

    @Bean(name = CLICK_EXECUTOR)
    public Executor clickExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(10_000);
        ex.setThreadNamePrefix("click-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(5);
        ex.initialize();
        return ex;
    }
}
