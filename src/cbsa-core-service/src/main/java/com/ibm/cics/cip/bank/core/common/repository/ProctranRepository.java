package com.ibm.cics.cip.bank.core.common.repository;

import static com.ibm.cics.cip.bank.core.common.repository.CustomerRepository.toLocal;
import static com.ibm.cics.cip.bank.core.common.repository.CustomerRepository.toSql;

import com.ibm.cics.cip.bank.core.common.domain.ProcessedTransaction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for DB2 table PROCTRAN. Write through {@code ProctranWriter}, not directly. */
@Repository
public class ProctranRepository {

    private static final String COLUMNS = "PROCTRAN_EYECATCHER, PROCTRAN_SORTCODE, PROCTRAN_NUMBER, PROCTRAN_DATE, "
            + "PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE, PROCTRAN_DESC, PROCTRAN_AMOUNT";

    private final JdbcClient jdbc;

    public ProctranRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ProcessedTransaction p) {
        jdbc.sql("INSERT INTO PROCTRAN (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(p.eyecatcher(), p.sortCode(), p.number(), toSql(p.date()), p.time(), p.reference(), p.type(),
                        p.description(), p.amount())
                .update();
    }

    public List<ProcessedTransaction> findAll(int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM PROCTRAN ORDER BY PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF "
                        + "LIMIT ? OFFSET ?")
                .params(limit, offset)
                .query(ProctranRepository::map)
                .list();
    }

    /**
     * Rows of one bank, oldest first: the Liberty {@code ProcessedTransaction.getProcessedTransactions} query
     * ({@code WHERE PROCTRAN_SORTCODE LIKE ? ORDER BY PROCTRAN_DATE, PROCTRAN_TIME}, rows offset+1..offset+limit),
     * with PROCTRAN_REF / PROCTRAN_NUMBER as tie-breakers so paging is deterministic.
     */
    public List<ProcessedTransaction> findBySortCode(String sortCode, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM PROCTRAN WHERE PROCTRAN_SORTCODE = ? "
                        + "ORDER BY PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_NUMBER LIMIT ? OFFSET ?")
                .params(sortCode, limit, offset)
                .query(ProctranRepository::map)
                .list();
    }

    public List<ProcessedTransaction> findByNumber(String sortCode, String number) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM PROCTRAN WHERE PROCTRAN_SORTCODE = ? AND PROCTRAN_NUMBER = ? "
                        + "ORDER BY PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF")
                .params(sortCode, number)
                .query(ProctranRepository::map)
                .list();
    }

    public List<ProcessedTransaction> findByType(String type) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM PROCTRAN WHERE PROCTRAN_TYPE = ? "
                        + "ORDER BY PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF")
                .param(type)
                .query(ProctranRepository::map)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM PROCTRAN").query(Long.class).single();
    }

    private static ProcessedTransaction map(ResultSet rs, int row) throws SQLException {
        // Fixed-width CHAR columns are kept as stored (PROCTRAN_DESC layouts are positional).
        return new ProcessedTransaction(
                rs.getString("PROCTRAN_EYECATCHER"),
                rs.getString("PROCTRAN_SORTCODE"),
                rs.getString("PROCTRAN_NUMBER"),
                toLocal(rs.getDate("PROCTRAN_DATE")),
                rs.getString("PROCTRAN_TIME"),
                rs.getString("PROCTRAN_REF"),
                rs.getString("PROCTRAN_TYPE"),
                rs.getString("PROCTRAN_DESC"),
                rs.getBigDecimal("PROCTRAN_AMOUNT"));
    }
}
