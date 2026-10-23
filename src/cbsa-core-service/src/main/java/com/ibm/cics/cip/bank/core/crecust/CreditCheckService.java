package com.ibm.cics.cip.bank.core.crecust;

import com.ibm.cics.cip.bank.core.common.config.CbsaProperties;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.proctran.TaskNumberSupplier;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Port of CRECUST {@code CREDIT-CHECK / CC010} (R-C3). The legacy program PUTs the customer into containers
 * CIPA..CIPE, starts CRDTAGY1..5 as child tasks ({@code RUN TRANSID('OCR1'..'OCR5') CHILD}),
 * {@code DELAY FOR SECONDS(3)}, then {@code FETCH ANY NOSUSPEND}s every finished child. Here the five
 * {@link CreditAgency} beans run in parallel as {@link CompletableFuture}s; agencies that have not answered
 * after the 3-second window are ignored, as the legacy loop stops at the first NOTFINISHED.
 *
 * <ul>
 * <li>credit score = {@code INT(total / answered)};</li>
 * <li>review date = today + {@code INT((21 - 1) * RANDOM(EIBTASKN)) + 1} days (1-20);</li>
 * <li>no answer -> fail code {@code G}; an agency failure (child ABEND) -> {@code F}.</li>
 * </ul>
 * The 3-second window is waited in full only when {@code cbsa.credit.simulate-delay=true} (the agencies then
 * also sleep their 1-2 s); otherwise the agencies answer immediately and the window is only an upper bound.
 */
@Service
public class CreditCheckService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CreditCheckService.class);

    /** CC010 {@code EXEC CICS DELAY FOR SECONDS(3)} before fetching the children. */
    public static final Duration FETCH_WINDOW = Duration.ofSeconds(3);

    static final int REVIEW_MIN_DAYS = 1;
    static final int REVIEW_MAX_DAYS_EXCLUSIVE = 21;

    private final List<CreditAgency> agencies;
    private final CreditRandomSource randoms;
    private final TaskNumberSupplier taskNumbers;
    private final Clock clock;
    private final boolean simulateDelay;
    private final Duration fetchWindow;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public CreditCheckService(List<CreditAgency> agencies, CreditRandomSource randoms, TaskNumberSupplier taskNumbers,
            Clock clock, CbsaProperties properties) {
        this(agencies, randoms, taskNumbers, clock,
                properties.credit() != null && properties.credit().simulateDelay(), FETCH_WINDOW);
    }

    CreditCheckService(List<CreditAgency> agencies, CreditRandomSource randoms, TaskNumberSupplier taskNumbers,
            Clock clock, boolean simulateDelay, Duration fetchWindow) {
        this.agencies = agencies.stream().sorted(Comparator.comparingInt(CreditAgency::number)).toList();
        this.randoms = randoms;
        this.taskNumbers = taskNumbers;
        this.clock = clock;
        this.simulateDelay = simulateDelay;
        this.fetchWindow = fetchWindow;
    }

    public boolean isSimulatingDelay() {
        return simulateDelay;
    }

    /** Credit check under the current task number (EIBTASKN). */
    public CreditCheckResult check(Customer customer) {
        return check(customer, taskNumbers.currentTaskNumber());
    }

    /** Credit check as run by the CRECUST task {@code crecustTaskNumber}. */
    public CreditCheckResult check(Customer customer, long crecustTaskNumber) {
        LocalDate today = LocalDate.now(clock);
        long started = System.nanoTime();
        List<CompletableFuture<AgencyAnswer>> children = agencies.stream()
                .map(agency -> CompletableFuture.supplyAsync(
                        () -> agency.assess(customer, crecustTaskNumber, randoms, simulateDelay), executor))
                .toList();

        if (!awaitFetchWindow(children, started)) {
            children.forEach(c -> c.cancel(true));
            return CreditCheckResult.failure(CreditCheckResult.FAIL_NO_ANSWER, today, List.of());
        }

        List<AgencyAnswer> answers = new ArrayList<>();
        boolean abended = false;
        for (CompletableFuture<AgencyAnswer> child : children) {
            if (!child.isDone()) {
                child.cancel(true);
            } else if (child.isCompletedExceptionally()) {
                abended = true;
                log.warn("CRECUST credit check: a credit agency failed", exceptionOf(child));
            } else {
                answers.add(child.join());
            }
        }
        if (abended) {
            return CreditCheckResult.failure(CreditCheckResult.FAIL_AGENCY_ABEND, today, answers);
        }
        if (answers.isEmpty()) {
            return CreditCheckResult.failure(CreditCheckResult.FAIL_NO_ANSWER, today, answers);
        }
        int total = answers.stream().mapToInt(AgencyAnswer::score).sum();
        int score = total / answers.size();
        int reviewDays = CreditRandomSource.legacyInt(randoms.reviewDateStream(crecustTaskNumber), REVIEW_MIN_DAYS,
                REVIEW_MAX_DAYS_EXCLUSIVE);
        return CreditCheckResult.success(score, today.plusDays(reviewDays), answers);
    }

    /** Returns false when the waiting thread was interrupted. */
    private boolean awaitFetchWindow(List<CompletableFuture<AgencyAnswer>> children, long startedNanos) {
        long remaining = fetchWindow.toNanos() - (System.nanoTime() - startedNanos);
        try {
            if (simulateDelay) {
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.sleep(remaining);
                }
            } else if (remaining > 0) {
                CompletableFuture.allOf(children.toArray(CompletableFuture[]::new))
                        .get(remaining, TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            // collected per child below
        }
        return true;
    }

    private static Throwable exceptionOf(CompletableFuture<?> child) {
        try {
            child.join();
            return null;
        } catch (RuntimeException e) {
            return e.getCause() != null ? e.getCause() : e;
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
