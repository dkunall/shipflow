package com.shipflow.allocation.application;

import com.shipflow.allocation.domain.AllocationEngine;
import com.shipflow.allocation.domain.AllocationRule;
import com.shipflow.allocation.domain.CandidateFilter;
import com.shipflow.config.ShipFlowProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AllocationConfig {
    /** Spring injects filters/rules sorted by @Order: composition of strategies, no switch chains. */
    @Bean
    public AllocationEngine allocationEngine(List<CandidateFilter> filters, List<AllocationRule> rules,
                                             ShipFlowProperties props) {
        return new AllocationEngine(filters, rules, props.allocation()::weight);
    }
}
