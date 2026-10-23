package com.ibm.cics.cip.bank.core.common.repository;

import com.ibm.cics.cip.bank.core.common.domain.ControlRecord;
import java.util.Optional;
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
