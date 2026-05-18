package com.livenotification.global.config;

import com.livenotification.delivery.domain.RetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Configuration
public class WorkerConfig {

    @Bean
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public Semaphore dispatchSemaphore(NotificationProperties properties) {
        return new Semaphore(properties.worker().semaphorePermits(), true);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RetryPolicy retryPolicy(NotificationProperties properties) {
        return new RetryPolicy(
            properties.retry().baseDelay(),
            properties.retry().maxAttempts(),
            properties.retry().jitterFraction());
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("notif-sched-");
        scheduler.initialize();
        return scheduler;
    }
}
