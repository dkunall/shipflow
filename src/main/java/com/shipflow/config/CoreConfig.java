package com.shipflow.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
public class CoreConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Background jobs (webhook dispatch, booking reconciliation, COD reconciliation); off in tests. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "shipflow.scheduling.enabled", havingValue = "true")
    static class SchedulingConfig {
    }
}
