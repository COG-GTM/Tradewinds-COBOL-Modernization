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
    void childTasksAreReservedAndUnscopedCallsAllocateFreshNumbers() {
        TaskNumberSupplier tasks = new TaskNumberSupplier();
        tasks.restartAt(100);
        tasks.reserveChildTasks(100, 5);
        assertThat(tasks.currentTaskNumber()).isEqualTo(106);
        assertThat(tasks.currentTaskNumber()).isEqualTo(107);
    }
}
