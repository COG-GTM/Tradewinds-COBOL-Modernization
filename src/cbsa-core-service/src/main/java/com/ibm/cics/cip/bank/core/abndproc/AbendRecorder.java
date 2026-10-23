package com.ibm.cics.cip.bank.core.abndproc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ibm.cics.cip.bank.core.common.error.AbendEvent;

/**
 * ABNDPROC: the legacy program WRITEs every abend record passed in its COMMAREA to the ABNDFILE KSDS. In the
 * Java service every {@link AbendEvent} (published when a {@code CbsaAbendException} is handled) is kept in a
 * bounded in-memory journal, newest first.
 */
@Component
public class AbendRecorder {

    /** Maximum number of abend records retained. */
    public static final int CAPACITY = 1000;

    private final Deque<AbendEvent> journal = new ArrayDeque<>();

    /** PREMIERE: WRITE FILE('ABNDFILE'). */
    @EventListener
    public void record(AbendEvent event) {
        if (event == null) {
            return;
        }
        synchronized (journal) {
            journal.addFirst(event);
            while (journal.size() > CAPACITY) {
                journal.removeLast();
            }
        }
    }

    /** The {@code limit} most recent abend records, newest first. */
    public List<AbendEvent> recent(int limit) {
        int n = Math.max(0, limit);
        List<AbendEvent> out = new ArrayList<>(Math.min(n, CAPACITY));
        synchronized (journal) {
            Iterator<AbendEvent> it = journal.iterator();
            while (it.hasNext() && out.size() < n) {
                out.add(it.next());
            }
        }
        return out;
    }
}
