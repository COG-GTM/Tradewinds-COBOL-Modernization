package com.ibm.cics.cip.bank.core.common.error;

import java.time.Instant;

/**
 * Published (Spring ApplicationEvent) whenever a {@link CbsaAbendException} reaches the error handler.
 * Mirrors the ABNDINFO.cpy record that programs LINK to ABNDPROC with; the ABNDPROC ticket listens
 * for it to persist/expose abend records.
 */
public record AbendEvent(Instant time, String abendCode, String program, String freeform) {
}
