package com.ibm.cics.cip.bank.core.common.config;

/**
 * The documented deterministic replacement for COBOL {@code FUNCTION RANDOM} shared with the legacy harness
 * ({@code legacy/runtime/cbsastub.c}, baseline/README.md "Random"): the {@code java.util.Random} 48-bit LCG.
 *
 * <pre>
 * RANDOM(s) : state = (s XOR 0x5DEECE66D) mod 2^48          (s = CBSA_RANDOM_SEED + argument)
 * next(b)   : state = (state * 0x5DEECE66D + 0xB) mod 2^48 ; return state &gt;&gt;&gt; (48 - b)
 * RANDOM    : ((next(26) &lt;&lt; 27) + next(27)) / 2^53            (double in [0,1))
 * </pre>
 *
 * {@code new CbsaRandom(s).nextDouble()} equals {@code new java.util.Random(s).nextDouble()}. Streams (see
 * {@link CbsaRandomFactory}): stream k is seeded with (cbsa.random.seed + k); legacy programs use
 * k = EIBTASKN (+ n for CRDTAGY child n).
 */
public final class CbsaRandom {

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;
    private static final double DOUBLE_UNIT = 0x1.0p-53;

    private long state;

    public CbsaRandom(long seed) {
        this.state = (seed ^ MULTIPLIER) & MASK;
    }

    private int next(int bits) {
        state = (state * MULTIPLIER + ADDEND) & MASK;
        return (int) (state >>> (48 - bits));
    }

    public synchronized double nextDouble() {
        return (((long) next(26) << 27) + next(27)) * DOUBLE_UNIT;
    }

    /** {@code FUNCTION RANDOM} delivered into a {@code PIC V9(9)} item: the nine decimals as an integer. */
    public long nextV9x9() {
        return (long) (nextDouble() * 1_000_000_000.0);
    }

    /** Legacy {@code ((hi - lo) * RANDOM) + lo} truncated to an integer: result in [lo, hi). */
    public int nextInt(int lo, int hi) {
        return (int) Math.floor((hi - lo) * nextDouble()) + lo;
    }
}
