package com.ibm.cics.cip.bank.core.creacc;

import static org.assertj.core.api.Assertions.assertThat;

import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Concurrent creations for a customer holding nine accounts: exactly one may succeed (ten-account limit). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:creacc-concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42"})
class CreateAccountConcurrencyTest {

    static final int THREADS = 4;

    @Autowired
    CreateAccountService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void tenAccountLimitHoldsUnderConcurrency() throws Exception {
        long existing = count();
        for (long i = existing; i < 9; i++) {
            jdbc.update("INSERT INTO ACCOUNT VALUES ('ACCT','0000000030','987654',?,'SAVING',1.0,'2020-01-01',0,"
                    + "'2025-12-01','2026-01-01',10,10)", String.format("%08d", 500 + i));
        }
        assertThat(count()).isEqualTo(9);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<String>> results = new ArrayList<>();
        Callable<String> create = () -> {
            start.await();
            try {
                service.createAccount(30, "SAVING", BigDecimal.ONE, 0, BigDecimal.ZERO, BigDecimal.ZERO);
                return "Y";
            } catch (CbsaException e) {
                return e.failCode();
            }
        };
        for (int i = 0; i < THREADS; i++) {
            results.add(pool.submit(create));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        assertThat(outcomes).containsOnlyOnce("Y").containsOnly("Y", "8");
        assertThat(count()).isEqualTo(10);
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_CUSTOMER_NUMBER='0000000030'",
                Long.class);
    }
}
