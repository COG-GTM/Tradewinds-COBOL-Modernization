package com.tradewinds.tradedesk.dbcrfun.config;

import com.tradewinds.tradedesk.dbcrfun.service.ProcTranReferenceGenerator;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DbcrfunConfig {

    private static final long REF_MODULUS = 1_000_000_000_000L;

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcTranReferenceGenerator procTranReferenceGenerator(Clock clock) {
        AtomicLong counter = new AtomicLong(clock.millis() % REF_MODULUS);
        return () -> String.format("%012d", Math.floorMod(counter.incrementAndGet(), REF_MODULUS));
    }
}
