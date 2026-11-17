package com.tradewinds.tradedesk.dbcrfun;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Replacement for EIBTASKN (PIC S9(7) COMP-3), used as the PROCTRAN reference.
 * Wraps after 9,999,999 like the CICS task number.
 */
@Component
public class TaskNumberSource {

    private static final long MAX_TASK_NUMBER = 9_999_999L;

    private final AtomicLong counter = new AtomicLong();

    public long next() {
        return counter.updateAndGet(n -> n >= MAX_TASK_NUMBER ? 1 : n + 1);
    }
}
