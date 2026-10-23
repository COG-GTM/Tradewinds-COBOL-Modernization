package com.ibm.cics.cip.bank.core.crecust;

import com.ibm.cics.cip.bank.core.common.domain.Customer;
import java.util.function.DoubleSupplier;

/**
 * Port of CRDTAGY1..5 {@code PREMIERE / A010}: the five programs are identical apart from their container
 * name (CIPA..CIPE). Each computes {@code WS-DELAY-AMT = ((3 - 1) * RANDOM(EIBTASKN)) + 1} (1-2 s),
 * {@code EXEC CICS DELAY}s, GETs the customer container, sets
 * {@code WS-NEW-CREDSCORE = ((999 - 1) * RANDOM) + 1} (1-998) and PUTs it back.
 */
public class CreditAgency {

    static final int MIN_DELAY = 1;
    static final int MAX_DELAY_EXCLUSIVE = 3;
    static final int MIN_SCORE = 1;
    static final int MAX_SCORE_EXCLUSIVE = 999;

    private final int number;

    public CreditAgency(int number) {
        if (number < 1 || number > 5) {
            throw new IllegalArgumentException("Credit agency number must be 1..5: " + number);
        }
        this.number = number;
    }

    public int number() {
        return number;
    }

    /** Legacy program name, e.g. {@code CRDTAGY3}. */
    public String programName() {
        return "CRDTAGY" + number;
    }

    /** Legacy container name: CIPA..CIPE. */
    public String containerName() {
        return "CIP" + (char) ('A' + number - 1);
    }

    /**
     * A010: draw the delay, wait it when {@code simulateDelay}, then draw the score.
     *
     * @param customer          the CIPx container content (unused by the legacy scoring)
     * @param crecustTaskNumber EIBTASKN of the parent CRECUST task
     */
    public AgencyAnswer assess(Customer customer, long crecustTaskNumber, CreditRandomSource randoms,
            boolean simulateDelay) {
        DoubleSupplier random = randoms.agencyStream(number, crecustTaskNumber);
        int delay = CreditRandomSource.legacyInt(random, MIN_DELAY, MAX_DELAY_EXCLUSIVE);
        if (simulateDelay) {
            try {
                Thread.sleep(delay * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(programName() + " DELAY interrupted (abend PLOP)", e);
            }
        }
        int score = CreditRandomSource.legacyInt(random, MIN_SCORE, MAX_SCORE_EXCLUSIVE);
        return new AgencyAnswer(number, programName(), delay, score);
    }
}
