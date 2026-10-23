package com.ibm.cics.cip.bank.core.common.repository;

import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for table CUSTOMER (replaces EXEC CICS READ/WRITE/REWRITE/DELETE FILE('CUSTOMER')). */
@Repository
public class CustomerRepository {

    private static final String COLUMNS = "CUSTOMER_EYECATCHER, CUSTOMER_SORTCODE, CUSTOMER_NUMBER, CUSTOMER_NAME, "
            + "CUSTOMER_ADDRESS, CUSTOMER_DATE_OF_BIRTH, CUSTOMER_CREDIT_SCORE, CUSTOMER_CS_REVIEW_DATE";

    private final JdbcClient jdbc;

    public CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Customer> findByKey(String sortCode, long customerNumber) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?")
                .params(sortCode, CbsaFormats.customerNumber(customerNumber))
                .query(CustomerRepository::map)
                .optional();
    }

    /** {@code SELECT ... FOR UPDATE}: reads and row-locks the customer until the caller's transaction ends. */
    public Optional<Customer> findByKeyForUpdate(String sortCode, long customerNumber) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?"
                        + " FOR UPDATE")
                .params(sortCode, CbsaFormats.customerNumber(customerNumber))
                .query(CustomerRepository::map)
                .optional();
    }

    public boolean exists(String sortCode, long customerNumber) {
        return findByKey(sortCode, customerNumber).isPresent();
    }

    /** Case-insensitive substring match on CUSTOMER_NAME, ordered by customer number. */
    public List<Customer> findByNameContaining(String sortCode, String namePart, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ? "
                        + "AND UPPER(CUSTOMER_NAME) LIKE ? ESCAPE '!' ORDER BY CUSTOMER_NUMBER LIMIT ? OFFSET ?")
                .params(sortCode, likePattern(namePart), limit, offset)
                .query(CustomerRepository::map)
                .list();
    }

    public long countByNameContaining(String sortCode, String namePart) {
        return jdbc.sql("SELECT COUNT(*) FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ? AND UPPER(CUSTOMER_NAME) LIKE ? ESCAPE '!'")
                .params(sortCode, likePattern(namePart))
                .query(Long.class)
                .single();
    }

    public List<Customer> findAll(String sortCode, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ? "
                        + "ORDER BY CUSTOMER_NUMBER LIMIT ? OFFSET ?")
                .params(sortCode, limit, offset)
                .query(CustomerRepository::map)
                .list();
    }

    public long count(String sortCode) {
        return jdbc.sql("SELECT COUNT(*) FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ?")
                .param(sortCode).query(Long.class).single();
    }

    /** Highest customer number in use (INQCUST 9999999999 / GET-LAST-CUSTOMER-VSAM), 0 when empty. */
    public long maxCustomerNumber(String sortCode) {
        String max = jdbc.sql("SELECT MAX(CUSTOMER_NUMBER) FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ?")
                .param(sortCode).query(String.class).optional().orElse(null);
        return CbsaFormats.toLong(max);
    }

    public void insert(Customer c) {
        jdbc.sql("INSERT INTO CUSTOMER (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(c.eyecatcher(), c.sortCode(), CbsaFormats.customerNumber(c.customerNumber()), c.name(),
                        c.address(), toSql(c.dateOfBirth()), c.creditScore(), toSql(c.creditScoreReviewDate()))
                .update();
    }

    /** REWRITE of all non-key columns; returns rows updated (0 = NOTFND). */
    public int update(Customer c) {
        return jdbc.sql("UPDATE CUSTOMER SET CUSTOMER_EYECATCHER = ?, CUSTOMER_NAME = ?, CUSTOMER_ADDRESS = ?, "
                        + "CUSTOMER_DATE_OF_BIRTH = ?, CUSTOMER_CREDIT_SCORE = ?, CUSTOMER_CS_REVIEW_DATE = ? "
                        + "WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?")
                .params(c.eyecatcher(), c.name(), c.address(), toSql(c.dateOfBirth()), c.creditScore(),
                        toSql(c.creditScoreReviewDate()), c.sortCode(), CbsaFormats.customerNumber(c.customerNumber()))
                .update();
    }

    public int delete(String sortCode, long customerNumber) {
        return jdbc.sql("DELETE FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?")
                .params(sortCode, CbsaFormats.customerNumber(customerNumber))
                .update();
    }

    private static String likePattern(String namePart) {
        String p = namePart == null ? "" : namePart.trim().toUpperCase();
        return "%" + p.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    static Date toSql(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    static LocalDate toLocal(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    static String trim(String s) {
        return s == null ? null : s.strip();
    }

    private static Customer map(ResultSet rs, int row) throws SQLException {
        return new Customer(
                trim(rs.getString("CUSTOMER_EYECATCHER")),
                trim(rs.getString("CUSTOMER_SORTCODE")),
                CbsaFormats.toLong(rs.getString("CUSTOMER_NUMBER")),
                trim(rs.getString("CUSTOMER_NAME")),
                trim(rs.getString("CUSTOMER_ADDRESS")),
                toLocal(rs.getDate("CUSTOMER_DATE_OF_BIRTH")),
                rs.getInt("CUSTOMER_CREDIT_SCORE"),
                toLocal(rs.getDate("CUSTOMER_CS_REVIEW_DATE")));
    }
}
