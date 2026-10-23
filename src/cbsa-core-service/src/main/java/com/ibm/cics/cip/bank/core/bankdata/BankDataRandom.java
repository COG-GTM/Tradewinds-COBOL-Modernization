package com.ibm.cics.cip.bank.core.bankdata;

import com.ibm.cics.cip.bank.core.common.config.CbsaRandom;

/**
 * {@code FUNCTION RANDOM} exactly as the legacy harness implements it, via the shared {@link CbsaRandom}
 * ({@code java.util.Random}-compatible 48-bit LCG, baseline/README.md "Random"). The value is delivered into
 * {@code CBSA-RND-VAL PIC V9(9)}, so it is kept as the integer {@code n = floor(d * 10^9)} and COBOL
 * {@code COMPUTE} truncation is reproduced with exact integer arithmetic.
 */
final class BankDataRandom {

    static final long SCALE = 1_000_000_000L;

    private final CbsaRandom random;

    /** {@code FUNCTION RANDOM(seed)}: reseeds (the first value is returned by the next {@link #next9()}). */
    BankDataRandom(long seed) {
        this.random = new CbsaRandom(seed);
    }

    /** {@code FUNCTION RANDOM} as {@code PIC V9(9)}: the nine decimal digits as an integer in [0, 10^9). */
    long next9() {
        return random.nextV9x9();
    }

    /** {@code COMPUTE X = (range * FUNCTION RANDOM) + lo} into an integer PIC (truncation). */
    long scaled(long range, long lo) {
        return Math.floorDiv(range * next9(), SCALE) + lo;
    }
}
