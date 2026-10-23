package com.ibm.cics.cip.bank.core.common.repository;

import static com.ibm.cics.cip.bank.core.common.repository.CustomerRepository.toLocal;
import static com.ibm.cics.cip.bank.core.common.repository.CustomerRepository.toSql;
import static com.ibm.cics.cip.bank.core.common.repository.CustomerRepository.trim;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for DB2 table ACCOUNT (the EXEC SQL statements of INQACC/CREACC/UPDACC/DELACC/DBCRFUN/XFRFUN). */
@Repository
public class AccountRepository {

    private static final String COLUMNS = "ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER, ACCOUNT_SORTCODE, "
            + "ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OPENED, ACCOUNT_OVERDRAFT_LIMIT, "
            + "ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT, ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE";

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findByKey(String sortCode, long accountNumber) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?")
                .params(sortCode, CbsaFormats.accountNumber(accountNumber))
                .query(AccountRepository::map)
                .optional();
    }

    /** {@code SELECT ... FOR UPDATE}: reads and row-locks the account until the caller's transaction ends. */
    public Optional<Account> findByKeyForUpdate(String sortCode, long accountNumber) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?"
                        + " FOR UPDATE")
                .params(sortCode, CbsaFormats.accountNumber(accountNumber))
                .query(AccountRepository::map)
                .optional();
    }

    /** INQACCCU cursor: accounts of a customer ordered by account number. */
    public List<Account> findByCustomer(String sortCode, long customerNumber) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? "
                        + "AND ACCOUNT_CUSTOMER_NUMBER = ? ORDER BY ACCOUNT_NUMBER")
                .params(sortCode, CbsaFormats.customerNumber(customerNumber))
                .query(AccountRepository::map)
                .list();
    }

    public long countByCustomer(String sortCode, long customerNumber) {
        return jdbc.sql("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_CUSTOMER_NUMBER = ?")
                .params(sortCode, CbsaFormats.customerNumber(customerNumber))
                .query(Long.class).single();
    }

    public List<Account> findAll(String sortCode, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? "
                        + "ORDER BY ACCOUNT_NUMBER LIMIT ? OFFSET ?")
                .params(sortCode, limit, offset)
                .query(AccountRepository::map)
                .list();
    }

    public long count(String sortCode) {
        return jdbc.sql("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ?")
                .param(sortCode).query(Long.class).single();
    }

    public long maxAccountNumber(String sortCode) {
        String max = jdbc.sql("SELECT MAX(ACCOUNT_NUMBER) FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ?")
                .param(sortCode).query(String.class).optional().orElse(null);
        return CbsaFormats.toLong(max);
    }

    public void insert(Account a) {
        jdbc.sql("INSERT INTO ACCOUNT (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(a.eyecatcher(), CbsaFormats.customerNumber(a.customerNumber()), a.sortCode(),
                        CbsaFormats.accountNumber(a.accountNumber()), a.accountType(), a.interestRate(),
                        toSql(a.opened()), a.overdraftLimit(), toSql(a.lastStatement()), toSql(a.nextStatement()),
                        a.availableBalance(), a.actualBalance())
                .update();
    }

    /** UPDACC: type, interest rate and overdraft only. Returns rows updated. */
    public int updateTypeRateOverdraft(String sortCode, long accountNumber, String type, BigDecimal rate,
            int overdraft) {
        return jdbc.sql("UPDATE ACCOUNT SET ACCOUNT_TYPE = ?, ACCOUNT_INTEREST_RATE = ?, ACCOUNT_OVERDRAFT_LIMIT = ? "
                        + "WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?")
                .params(type, rate, overdraft, sortCode, CbsaFormats.accountNumber(accountNumber))
                .update();
    }

    /** DBCRFUN / XFRFUN: set both balances. Returns rows updated. */
    public int updateBalances(String sortCode, long accountNumber, BigDecimal available, BigDecimal actual) {
        return jdbc.sql("UPDATE ACCOUNT SET ACCOUNT_AVAILABLE_BALANCE = ?, ACCOUNT_ACTUAL_BALANCE = ? "
                        + "WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?")
                .params(available, actual, sortCode, CbsaFormats.accountNumber(accountNumber))
                .update();
    }

    public int delete(String sortCode, long accountNumber) {
        return jdbc.sql("DELETE FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?")
                .params(sortCode, CbsaFormats.accountNumber(accountNumber))
                .update();
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        return new Account(
                trim(rs.getString("ACCOUNT_EYECATCHER")),
                CbsaFormats.toLong(rs.getString("ACCOUNT_CUSTOMER_NUMBER")),
                trim(rs.getString("ACCOUNT_SORTCODE")),
                CbsaFormats.toLong(rs.getString("ACCOUNT_NUMBER")),
                trim(rs.getString("ACCOUNT_TYPE")),
                rs.getBigDecimal("ACCOUNT_INTEREST_RATE"),
                toLocal(rs.getDate("ACCOUNT_OPENED")),
                rs.getInt("ACCOUNT_OVERDRAFT_LIMIT"),
                toLocal(rs.getDate("ACCOUNT_LAST_STATEMENT")),
                toLocal(rs.getDate("ACCOUNT_NEXT_STATEMENT")),
                rs.getBigDecimal("ACCOUNT_AVAILABLE_BALANCE"),
                rs.getBigDecimal("ACCOUNT_ACTUAL_BALANCE"));
    }
}
