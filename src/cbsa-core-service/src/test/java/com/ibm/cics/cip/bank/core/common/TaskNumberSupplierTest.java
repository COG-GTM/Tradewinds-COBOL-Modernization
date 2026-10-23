package com.ibm.cics.cip.bank.core.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.ibm.cics.cip.bank.core.common.proctran.TaskNumberSupplier;
import org.junit.jupiter.api.Test;

/** EIBTASKN emulation: one number per CICS task, allocated lazily, LINKs join the caller's task. */
class TaskNumberSupplierTest {

    @Test
    void oneTaskNumberPerTaskAndNestedTasksJoinTheCaller() {
        TaskNumberSupplier tasks = new TaskNumberSupplier();
        tasks.restartAt(100);
        long[] seen = new long[3];
        tasks.inTask(() -> {
            seen[0] = tasks.currentTaskNumber();
            seen[1] = tasks.inTask(tasks::currentTaskNumber);
            seen[2] = Long.parseLong(tasks.currentReference());
            return null;
        });
        assertThat(seen).containsExactly(100, 100, 100);
        assertThat(tasks.inTask(tasks::currentReference)).isEqualTo("000000000101");
    }

    @Test
    void tasksThatNeverAskForANumberDoNotConsumeOne() {
        TaskNumberSupplier tasks = new TaskNumberSupplier();
        tasks.restartAt(100);
        tasks.inTask(() -> null);
        assertThat(tasks.inTask(tasks::currentTaskNumber)).isEqualTo(100);
    }

    @Test
    void childTasksAreAllocatedWithTheParentAndUnscopedCallsAllocateFreshNumbers() {
        TaskNumberSupplier tasks = new TaskNumberSupplier();
        tasks.restartAt(100);
        long[] seen = new long[2];
        tasks.inTask(() -> {
            seen[0] = tasks.currentTaskNumberWithChildTasks(5);
            seen[1] = tasks.currentTaskNumber();
            return null;
        });
        assertThat(seen).containsExactly(100, 100);
        assertThat(tasks.currentTaskNumber()).isEqualTo(106);
        assertThat(tasks.currentTaskNumber()).isEqualTo(107);
    }

    @Test
    void concurrentParentsNeverReceiveEachOthersChildNumbers() throws Exception {
        TaskNumberSupplier tasks = new TaskNumberSupplier();
        java.util.Set<Long> used = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean overlap = new java.util.concurrent.atomic.AtomicBoolean();
        for (int t = 0; t < 8; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    long parent = tasks.inTask(() -> tasks.currentTaskNumberWithChildTasks(5));
                    for (long n = parent; n <= parent + 5; n++) {
                        if (!used.add(n)) {
                            overlap.set(true);
                        }
                    }
                    if (!used.add(tasks.inTask(tasks::currentTaskNumber))) {
                        overlap.set(true);
                    }
                }
            });
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) {
            th.join();
        }
        assertThat(overlap).isFalse();
    }
}
