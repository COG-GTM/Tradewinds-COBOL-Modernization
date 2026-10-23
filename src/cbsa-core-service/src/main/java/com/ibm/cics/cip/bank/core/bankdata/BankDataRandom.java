package com.ibm.cics.cip.bank.core.bankdata;

/**
 * {@code FUNCTION RANDOM} exactly as the legacy harness implements it ({@code legacy/runtime/cbsastub.c},
 * documented in baseline/README.md "Random"), i.e. the {@code java.util.Random} 48-bit LCG:
 *
 * <pre>
 * RANDOM(s) : state = ((CBSA_RANDOM_SEED + s) XOR 0x5DEECE66D) mod 2^48, then as RANDOM
 * RANDOM    : next(bits) = (state = (state * 0x5DEECE66D + 0xB) mod 2^48) &gt;&gt;&gt; (48 - bits)
 *             d = ((next(26) &lt;&lt; 27) + next(27)) / 2^53
 * </pre>
 *
 * The value is delivered into {@code CBSA-RND-VAL PIC V9(9)}, so it is truncated to nine decimals; this
 * class keeps it as the integer {@code n = floor(d * 10^9)} so COBOL {@code COMPUTE} truncation can be
 * reproduced with exact integer arithmetic.
 *
 * <p>Not {@code common.config.CbsaRandom}: that class currently implements a 31-bit LCG ("CBSA-LCG") that
 * does not match the harness, so it cannot reproduce baseline/seed/seed.sql (see the PR's integration
 * requests).
 */
final class BankDataRandom {

    static final long SCALE = 1_000_000_000L;

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;
    private static final double DOUBLE_UNIT = 0x1.0p-53;

    private long state;

    /** {@code FUNCTION RANDOM(seed)}: reseeds (the first value is returned by the next {@link #next9()}). */
    BankDataRandom(long seed) {
        this.state = (seed ^ MULTIPLIER) & MASK;
    }

    private int next(int bits) {
        state = (state * MULTIPLIER + ADDEND) & MASK;
        return (int) (state >>> (48 - bits));
    }

    /** {@code FUNCTION RANDOM} as {@code PIC V9(9)}: the nine decimal digits as an integer in [0, 10^9). */
    long next9() {
        double d = (((long) next(26) << 27) + next(27)) * DOUBLE_UNIT;
        return (long) (d * 1_000_000_000.0);
    }

    /** {@code COMPUTE X = (range * FUNCTION RANDOM) + lo} into an integer PIC (truncation). */
    long scaled(long range, long lo) {
        return Math.floorDiv(range * next9(), SCALE) + lo;
    }
}
