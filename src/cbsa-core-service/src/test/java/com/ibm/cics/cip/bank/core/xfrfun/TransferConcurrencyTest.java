package com.ibm.cics.cip.bank.core.xfrfun;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Concurrent opposite-direction transfers never lose an update or deadlock: money is conserved. */
@SpringBootTest(properties = {XfrfunTestSupport.FIXED_TIME, XfrfunTestSupport.RANDOM_SEED,
        XfrfunTestSupport.PRIVATE_DB})
class TransferConcurrencyTest {

    @Autowired
    TransferService service;

    @Autowired
    JdbcTemplate jdbc;

    private XfrfunTestSupport.Snapshot snapshot;

    @BeforeEach
    void snapshot() {
        snapshot = XfrfunTestSupport.snapshot(jdbc);
    }

    @AfterEach
    void restore() {
        XfrfunTestSupport.restore(jdbc, snapshot);
    }

    @Test
    void concurrentTransfersConserveMoney() throws Exception {
        BigDecimal[] a0 = XfrfunTestSupport.balances(jdbc, 10);
        BigDecimal[] b0 = XfrfunTestSupport.balances(jdbc, 11);
        int n = 40;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean forward = i % 2 == 0;
            calls.add(() -> {
                start.await();
                try {
                    service.transfer(forward ? 10 : 11, forward ? 11 : 10, BigDecimal.ONE);
                    return forward ? 1 : -1;
                } catch (CbsaException e) {
                    assertThat(e.failCode()).isEqualTo("3");
                    return 0;
                }
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> c : calls) {
            futures.add(pool.submit(c));
        }
        start.countDown();
        int net = 0;
        int ok = 0;
        for (Future<Integer> f : futures) {
            int v = f.get();
            net += v;
            ok += v != 0 ? 1 : 0;
        }
        pool.shutdown();

        BigDecimal[] a1 = XfrfunTestSupport.balances(jdbc, 10);
        BigDecimal[] b1 = XfrfunTestSupport.balances(jdbc, 11);
        assertThat(a1[0]).isEqualByComparingTo(a0[0].subtract(BigDecimal.valueOf(net)));
        assertThat(b1[0]).isEqualByComparingTo(b0[0].add(BigDecimal.valueOf(net)));
        assertThat(a1[1]).isEqualByComparingTo(a0[1].subtract(BigDecimal.valueOf(net)));
        assertThat(a1[0].add(b1[0])).isEqualByComparingTo(a0[0].add(b0[0]));
        assertThat(XfrfunTestSupport.inserted(snapshot.proctran(), XfrfunTestSupport.proctranRows(jdbc)))
                .hasSize(ok);
        assertThat(ok).isPositive();
    }
}
