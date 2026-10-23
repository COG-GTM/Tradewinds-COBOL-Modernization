package com.ibm.cics.cip.bank.core.common.proctran;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** EIBTASKN equivalent used for PROCTRAN_REF: a per-JVM sequence starting at 1. */
@Component
public class TaskNumberSupplier {

    private final AtomicLong next = new AtomicLong(1);

    public long nextTaskNumber() {
        return next.getAndIncrement();
    }

    /** 12-digit PROCTRAN_REF (WS-EIBTASKN12). */
    public String nextReference() {
        return String.format("%012d", nextTaskNumber());
    }
}
