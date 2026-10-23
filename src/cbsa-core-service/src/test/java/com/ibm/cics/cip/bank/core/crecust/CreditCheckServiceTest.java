package com.ibm.cics.cip.bank.core.crecust;

import static org.assertj.core.api.Assertions.assertThat;

import com.ibm.cics.cip.bank.core.common.config.CbsaRandom;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.proctran.TaskNumberSupplier;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class CreditCheckServiceTest {

    private static final Customer APPLICANT = new Customer("CUST", "987654", 0, "Mr Alan T Turing",
            "1 Bletchley Park", LocalDate.of(1912, 6, 23), 0, null);

    private static CreditCheckService service(CreditRandomSource randoms, List<CreditAgency> agencies,
            boolean simulateDelay, Duration window) {
        return new CreditCheckService(agencies, randoms, new TaskNumberSupplier(), TestSupport.FIXED, simulateDelay,
                window);
    }

    @Test
    void legacyAlgorithmReproducesTheBaselineScores() {
        // baseline/transcripts/CRECUST/ok-seed-42.json, ok-seed-7.json, ok-seed-1234-taskn-500.json
        assertCheck(42L, 100, 673, LocalDate.of(2026, 1, 30));
        assertCheck(7L, 100, 533, LocalDate.of(2026, 1, 30));
        assertCheck(1234L, 500, 528, LocalDate.of(2026, 1, 27));
    }

    private void assertCheck(long seed, long taskNumber, int score, LocalDate review) {
        CreditCheckService s = service(TestSupport.randoms(seed, "legacy"), TestSupport.agencies(), false,
                CreditCheckService.FETCH_WINDOW);
        CreditCheckResult r = s.check(APPLICANT, taskNumber);
        assertThat(r.ok()).isTrue();
        assertThat(r.score()).isEqualTo(score);
        assertThat(r.reviewDate()).isEqualTo(review);
        assertThat(r.answers()).hasSize(5).allSatisfy(a -> {
            assertThat(a.delaySeconds()).isBetween(1, 2);
            assertThat(a.score()).isBetween(1, 998);
        });
        s.destroy();
    }

    @Test
    void legacyAgencyStreamsAreJavaUtilRandomSeededWithSeedPlusTaskPlusAgency() {
        CreditCheckResult r = service(TestSupport.randoms(42L, "legacy"), TestSupport.agencies(), false,
                CreditCheckService.FETCH_WINDOW).check(APPLICANT, 100);
        for (AgencyAnswer a : r.answers()) {
            java.util.Random expected = new java.util.Random(42 + 100 + a.agency());
            assertThat(a.delaySeconds()).isEqualTo((int) (2 * expected.nextDouble()) + 1);
            assertThat(a.score()).isEqualTo((int) (998 * expected.nextDouble()) + 1);
        }
        assertThat(r.answers()).extracting(AgencyAnswer::programName)
                .containsExactly("CRDTAGY1", "CRDTAGY2", "CRDTAGY3", "CRDTAGY4", "CRDTAGY5");
    }

    @Test
    void cbsaLcgAlgorithmUsesTheFactoryStreams() {
        CreditCheckResult r = service(TestSupport.randoms(42L, "cbsa-lcg"), TestSupport.agencies(), false,
                CreditCheckService.FETCH_WINDOW).check(APPLICANT, 100);
        int total = 0;
        for (int n = 1; n <= 5; n++) {
            CbsaRandom stream = new CbsaRandom(42 + n);
            int delay = stream.nextInt(1, 3);
            int score = stream.nextInt(1, 999);
            total += score;
            assertThat(r.answers().get(n - 1).delaySeconds()).isEqualTo(delay);
            assertThat(r.answers().get(n - 1).score()).isEqualTo(score);
        }
        assertThat(r.score()).isEqualTo(total / 5);
        int days = new CbsaRandom(42).nextInt(1, 21);
        assertThat(r.reviewDate()).isEqualTo(LocalDate.of(2026, 1, 15).plusDays(days));
    }

    @Test
    void unseededChecksStayInLegacyRanges() {
        CreditCheckResult r = service(TestSupport.randoms(null, "legacy"), TestSupport.agencies(), false,
                CreditCheckService.FETCH_WINDOW).check(APPLICANT, 1);
        assertThat(r.ok()).isTrue();
        assertThat(r.score()).isBetween(1, 998);
        assertThat(r.reviewDate()).isBetween(LocalDate.of(2026, 1, 16), LocalDate.of(2026, 2, 4));
    }

    @Test
    void agenciesRunInParallelWithinTheThreeSecondWindow() {
        long start = System.nanoTime();
        CreditCheckResult r = service(TestSupport.randoms(42L, "legacy"), TestSupport.agencies(), true,
                CreditCheckService.FETCH_WINDOW).check(APPLICANT, 100);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(r.ok()).isTrue();
        assertThat(r.score()).isEqualTo(673);
        // 5 agencies x 2 s sequentially would be 10 s; in parallel the 3 s CRECUST DELAY dominates.
        assertThat(elapsedMs).isBetween(2_900L, 5_000L);
    }

    @Test
    void noAnswerWithinTheWindowFailsWithG() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        CreditAgency hanging = new CreditAgency(1) {
            @Override
            public AgencyAnswer assess(Customer c, long t, CreditRandomSource r, boolean d) {
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new AgencyAnswer(1, "CRDTAGY1", 9, 999);
            }
        };
        CreditCheckResult r = service(TestSupport.randoms(42L, "legacy"), List.of(hanging), false,
                Duration.ofMillis(200)).check(APPLICANT, 100);
        assertThat(r.ok()).isFalse();
        assertThat(r.failCode()).isEqualTo("G");
        assertThat(r.score()).isZero();
        assertThat(r.reviewDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void lateAgenciesAreIgnoredAndTheOthersAveraged() {
        CountDownLatch never = new CountDownLatch(1);
        CreditAgency late = new CreditAgency(5) {
            @Override
            public AgencyAnswer assess(Customer c, long t, CreditRandomSource r, boolean d) {
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new AgencyAnswer(5, "CRDTAGY5", 9, 1);
            }
        };
        List<CreditAgency> agencies = List.of(new CreditAgency(1), new CreditAgency(2), new CreditAgency(3),
                new CreditAgency(4), late);
        CreditCheckResult r = service(TestSupport.randoms(42L, "legacy"), agencies, false, Duration.ofMillis(300))
                .check(APPLICANT, 100);
        assertThat(r.ok()).isTrue();
        assertThat(r.answers()).hasSize(4);
        assertThat(r.score()).isEqualTo((404 + 825 + 994 + 487) / 4);
    }

    @Test
    void agencyFailureFailsWithF() {
        CreditAgency broken = new CreditAgency(2) {
            @Override
            public AgencyAnswer assess(Customer c, long t, CreditRandomSource r, boolean d) {
                throw new IllegalStateException("abend PLOP");
            }
        };
        List<CreditAgency> agencies = List.of(new CreditAgency(1), broken);
        CreditCheckResult r = service(TestSupport.randoms(42L, "legacy"), agencies, false,
                CreditCheckService.FETCH_WINDOW).check(APPLICANT, 100);
        assertThat(r.ok()).isFalse();
        assertThat(r.failCode()).isEqualTo("F");
    }
}
