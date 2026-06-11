package com.watchdog.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@ConditionalOnProperty(prefix = "watchdog.agent", name = "enabled", havingValue = "true")
@EnableAsync
public class AgentExecutorConfig {

    @Bean(name = "rcaExecutor")
    public Executor rcaExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("rca-");
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }
}
