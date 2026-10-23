package com.ibm.cics.cip.bank.core.common.proctran;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Emulates the CICS task number {@code EIBTASKN}. One REST request (one screen turn) is one CICS task: inside
 * {@link #inTask(Supplier)} every {@link #currentTaskNumber()} call returns the same number, allocated lazily from a
 * region-wide counter on first use, so all PROCTRAN rows written by one task carry the same {@code PROCTRAN_REF}
 * (as DELCUS writes ODA/ODC rows with one EIBTASKN). LINKed programs run in the caller's task; START'ed child tasks
 * (CRDTAGY1-5) run as parent + child id; {@link #currentTaskNumberWithChildTasks} allocates the parent and its children
 * in one atomic step so no concurrent task can be given a child's number. Outside a task
 * scope every call allocates a new number. {@link #restartAt(long)} sets the next number (CICS region restart; the
 * parity harness uses it to mirror {@code CBSA_TASKN}).
 */
@Component
public class TaskNumberSupplier {

    private final AtomicLong next = new AtomicLong(1);
    /** {task number (0 = not yet allocated), child task numbers reserved after it}. */
    private final ThreadLocal<long[]> task = new ThreadLocal<>();

    public long nextTaskNumber() {
        return next.getAndIncrement();
    }

    /** EIBTASKN of the current task (allocated on first use), or a fresh number outside a task scope. */
    public long currentTaskNumber() {
        long[] current = task.get();
        if (current == null) {
            return nextTaskNumber();
        }
        if (current[0] == 0) {
            current[0] = nextTaskNumber();
        }
        return current[0];
    }

    /**
     * EIBTASKN of the current task, allocating it together with {@code childTasks} child numbers
     * ({@code parent + 1 .. parent + childTasks}) in one atomic step when the task has no number yet.
     */
    public long currentTaskNumberWithChildTasks(int childTasks) {
        long[] current = task.get();
        if (current != null && current[0] != 0) {
            if (current[1] < childTasks) {
                // number taken earlier in this task: move the counter past the children (best effort, as CICS
                // cannot guarantee parent + n is free once other tasks have started)
                next.accumulateAndGet(current[0] + childTasks + 1, Math::max);
                current[1] = childTasks;
            }
            return current[0];
        }
        long parent = next.getAndAdd(1L + childTasks);
        if (current != null) {
            current[0] = parent;
            current[1] = childTasks;
        }
        return parent;
    }

    /** 12-digit PROCTRAN_REF (WS-EIBTASKN12) of the current task. */
    public String currentReference() {
        return String.format("%012d", currentTaskNumber());
    }

    /** Runs {@code work} as one CICS task; nested calls join the enclosing task (EXEC CICS LINK). */
    public <T> T inTask(Supplier<T> work) {
        if (task.get() != null) {
            return work.get();
        }
        task.set(new long[2]);
        try {
            return work.get();
        } finally {
            task.remove();
        }
    }

    /** The next task allocated gets {@code taskNumber}. */
    public void restartAt(long taskNumber) {
        next.set(taskNumber);
    }
}
