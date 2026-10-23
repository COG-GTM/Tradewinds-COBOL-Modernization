package com.ibm.cics.cip.bank.core.common.repository;

import com.ibm.cics.cip.bank.core.common.domain.ControlRecord;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for DB2 table CONTROL. Prefer {@code CounterService} for counters. */
@Repository
public class ControlRepository {

    private final JdbcClient jdbc;

    public ControlRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ControlRecord> find(String name) {
        return jdbc.sql("SELECT CONTROL_NAME, CONTROL_VALUE_NUM, CONTROL_VALUE_STR FROM CONTROL WHERE CONTROL_NAME = ?")
                .param(name)
                .query((rs, i) -> new ControlRecord(
                        rs.getString("CONTROL_NAME").strip(),
                        rs.getObject("CONTROL_VALUE_NUM") == null ? null : rs.getLong("CONTROL_VALUE_NUM"),
                        rs.getString("CONTROL_VALUE_STR") == null ? null : rs.getString("CONTROL_VALUE_STR").strip()))
                .optional();
    }

    /**
     * Atomically add {@code delta} to CONTROL_VALUE_NUM and return the new value. The UPDATE takes a row lock
     * that is held until the caller's transaction ends, so concurrent allocators (threads or service instances)
     * are serialised by the database. A missing row is created from {@code initial} first.
     */
    public long addAndGet(String name, long delta, LongSupplier initial) {
        if (increment(name, delta) == 0) {
            try {
                jdbc.sql("INSERT INTO CONTROL (CONTROL_NAME, CONTROL_VALUE_NUM, CONTROL_VALUE_STR) VALUES (?, ?, ?)")
                        .params(name, initial.getAsLong(), " ").update();
            } catch (DuplicateKeyException createdConcurrently) {
                // another transaction created the row first; fall through to the increment
            }
            increment(name, delta);
        }
        return jdbc.sql("SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME = ?")
                .param(name).query(Long.class).single();
    }

    private int increment(String name, long delta) {
        return jdbc.sql("UPDATE CONTROL SET CONTROL_VALUE_NUM = COALESCE(CONTROL_VALUE_NUM, 0) + ? WHERE CONTROL_NAME = ?")
                .params(delta, name).update();
    }

    /** Insert or update the numeric value of a control row. */
    public void upsertNum(String name, long value) {
        int updated = jdbc.sql("UPDATE CONTROL SET CONTROL_VALUE_NUM = ? WHERE CONTROL_NAME = ?")
                .params(value, name).update();
        if (updated == 0) {
            jdbc.sql("INSERT INTO CONTROL (CONTROL_NAME, CONTROL_VALUE_NUM, CONTROL_VALUE_STR) VALUES (?, ?, ?)")
                    .params(name, value, " ").update();
        }
    }
}
