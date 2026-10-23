package com.ibm.cics.cip.bank.core.common.config;

/**
 * "CBSA-LCG": the documented deterministic random generator shared with the legacy harness
 * (replaces COBOL FUNCTION RANDOM when CBSA_RANDOM_SEED / cbsa.random.seed is set).
 *
 * <pre>
 * state  = seed mod 2^31
 * next() : state = (state * 1103515245 + 12345) mod 2^31 ; return state / 2^31   (double in [0,1))
 * </pre>
 *
 * Streams (see {@link CbsaRandomFactory}): stream k is seeded with (cbsa.random.seed + k).
 * k=0 CRECUST review-date offset, k=1..5 CRDTAGY1..5.
 * COBOL "COMPUTE X = ((HI - 1) * FUNCTION RANDOM) + 1" into an integer PIC truncates, i.e.
 * {@link #nextInt(int, int)} = floor((hi - lo) * r) + lo.
 */
public final class CbsaRandom {

    private static final long MODULUS = 1L << 31;
    private static final long MULTIPLIER = 1103515245L;
    private static final long INCREMENT = 12345L;

    private long state;

    public CbsaRandom(long seed) {
        this.state = Math.floorMod(seed, MODULUS);
    }

    public synchronized double nextDouble() {
        state = (state * MULTIPLIER + INCREMENT) % MODULUS;
        return (double) state / (double) MODULUS;
    }

    /** Legacy {@code ((hi - lo) * RANDOM) + lo} truncated to an integer: result in [lo, hi). */
    public int nextInt(int lo, int hi) {
        return (int) Math.floor((hi - lo) * nextDouble()) + lo;
    }
}
