package com.tradewinds.tradedesk.dbcrfun;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.tradewinds.tradedesk.config.BankProperties;
import com.tradewinds.tradedesk.config.CobolText;

/**
 * Port of DBCRFUN (src/base/cobol_src/DBCRFUN.cbl): applies a counter (teller)
 * or PAYMENT debit/credit to an account and records it in PROCTRAN.
 * Line references are to DBCRFUN.cbl; PARITY.md maps every branch to a test.
 */
@Service
public class DbcrfunService {

    /** COMM-FACILTYPE value used by the PAYMENT interface (496 = NONE). */
    static final int FACILTYPE_PAYMENT = 496;

    static final String SUCCESS_YES = "Y";
    static final String SUCCESS_NO = "N";
    static final String FAIL_NONE = "0";
    static final String FAIL_NOT_FOUND = "1";
    static final String FAIL_DATASTORE = "2";
    static final String FAIL_INSUFFICIENT_FUNDS = "3";
    static final String FAIL_RESTRICTED_ACCOUNT = "4";

    /** DB2 SQLCODE for "connection lost" checked by CHECK-FOR-STORM-DRAIN-DB2. */
    static final int SQLCODE_CONNECTION_LOST = 923;

    private static final String ACCOUNT_TYPE_HOSPITAL = "HOSPITAL";
    private static final String ACCOUNT_TYPE_TENDER = "TENDER  ";
    private static final DateTimeFormatter PROCTRAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter PROCTRAN_TIME = DateTimeFormatter.ofPattern("HHmmss");

    private static final Logger LOG = LoggerFactory.getLogger(DbcrfunService.class);

    private final AccountRepository accountRepository;
    private final ProcTranRepository procTranRepository;
    private final BankProperties bankProperties;
    private final TaskNumberSource taskNumberSource;
    private final Clock clock;

    public DbcrfunService(AccountRepository accountRepository, ProcTranRepository procTranRepository,
            BankProperties bankProperties, TaskNumberSource taskNumberSource, Clock clock) {
        this.accountRepository = accountRepository;
        this.procTranRepository = procTranRepository;
        this.bankProperties = bankProperties;
        this.taskNumberSource = taskNumberSource;
        this.clock = clock;
    }

    @Transactional
    public DbcrfunResponse applyTransaction(DbcrfunRequest request) {
        // L211-212: COMM-SORTC is overwritten with the site SORTCODE; the input value is ignored.
        String sortcode = bankProperties.sortcodePic9();
        // L238: MOVE COMM-ACCNO TO DESIRED-ACC-NO (PIC 9(8)).
        String accNo = CobolText.moveToPic9(request.accNo(), 8);
        // COMM-AMT PIC S9(10)V99: excess decimals are truncated, as a COBOL MOVE would.
        BigDecimal amt = request.amt().setScale(2, RoundingMode.DOWN);
        DbcrfunOrigin origin = request.origin() != null ? request.origin()
                : new DbcrfunOrigin(null, null, null, null, 0);
        int facilType = origin.facilType();

        // L245-297: SELECT the account.
        Optional<Account> found;
        try {
            found = accountRepository.findById(new AccountId(sortcode, accNo));
        } catch (DataAccessException e) {
            LOG.error("DBCRFUN: SELECT from ACCOUNT failed for {}/{}", sortcode, accNo, e);
            checkForStormDrain(e);
            requestRollback();
            return failure(sortcode, FAIL_DATASTORE);
        }
        if (found.isEmpty()) {
            // L283-284: SQLCODE +100.
            return failure(sortcode, FAIL_NOT_FOUND);
        }
        Account account = found.get();

        // L307: IF COMM-AMT < 0 (zero is handled as a credit).
        if (amt.signum() < 0) {
            // L330-338: PAYMENT debit from a HOSPITAL/TENDER account.
            if (isRestrictedPayment(account, facilType)) {
                return failure(sortcode, FAIL_RESTRICTED_ACCOUNT);
            }
            // L340-350: PAYMENT debit with insufficient available funds. Tellers may overdraw.
            BigDecimal difference = account.getAvailableBalance().add(amt);
            if (difference.signum() < 0 && facilType == FACILTYPE_PAYMENT) {
                return failure(sortcode, FAIL_INSUFFICIENT_FUNDS);
            }
        }

        // L368-376: same HOSPITAL/TENDER check, applied to credits too.
        if (isRestrictedPayment(account, facilType)) {
            return failure(sortcode, FAIL_RESTRICTED_ACCOUNT);
        }

        // L384-387: apply the amount to both balances.
        BigDecimal newAvailable = account.getAvailableBalance().add(amt);
        BigDecimal newActual = account.getActualBalance().add(amt);
        account.setAvailableBalance(newAvailable);
        account.setActualBalance(newActual);

        // L392-435: UPDATE ACCOUNT. L416-419 return the new balances even if the UPDATE fails.
        try {
            accountRepository.saveAndFlush(account);
        } catch (DataAccessException e) {
            LOG.error("DBCRFUN: UPDATE of ACCOUNT failed for {}/{}", sortcode, accNo, e);
            checkForStormDrain(e);
            requestRollback();
            return new DbcrfunResponse(SUCCESS_NO, FAIL_DATASTORE, sortcode, newAvailable, newActual);
        }

        // L441, L460-652: write PROCTRAN.
        try {
            writeToProctranDb2(sortcode, accNo, amt, origin);
        } catch (ProctranWriteException e) {
            LOG.error("DBCRFUN: UNABLE TO WRITE TO PROCTRAN DB2 DATASTORE for {}/{}", sortcode, accNo, e);
            // L564: EXEC CICS SYNCPOINT ROLLBACK undoes the ACCOUNT update. The method still has to
            // return the COMMAREA, so instead of letting the exception escape the @Transactional
            // boundary the transaction is marked rollback-only and rolled back on return.
            requestRollback();
            checkForStormDrain(e.getCause());
            // L637: MOVE '02' TO COMM-FAIL-CODE. COMM-FAIL-CODE is PIC X, so COBOL keeps only the
            // leftmost character and the caller receives '0', not '2'. Reproduced on purpose.
            String failCode = CobolText.moveToPicX("02", 1);
            return new DbcrfunResponse(SUCCESS_NO, failCode, sortcode, newAvailable, newActual);
        }

        // L649-650.
        return new DbcrfunResponse(SUCCESS_YES, FAIL_NONE, sortcode, newAvailable, newActual);
    }

    /** L330-333 / L368-371: account type compared as PIC X(8), so 'TENDER' matches 'TENDER  '. */
    static boolean isRestrictedPayment(Account account, int facilType) {
        String accountType = CobolText.moveToPicX(account.getType(), 8);
        return facilType == FACILTYPE_PAYMENT
                && (ACCOUNT_TYPE_HOSPITAL.equals(accountType) || ACCOUNT_TYPE_TENDER.equals(accountType));
    }

    /** WRITE-TO-PROCTRAN-DB2 (L460-549). */
    private void writeToProctranDb2(String sortcode, String accNo, BigDecimal amt, DbcrfunOrigin origin) {
        LocalDateTime now = LocalDateTime.now(clock);

        ProcTran row = new ProcTran();
        row.setEyecatcher("PRTR");
        row.setSortcode(sortcode);
        row.setNumber(accNo);
        row.setRef(CobolText.moveToPic9(Long.toString(taskNumberSource.next()), 12));
        row.setDate(now.format(PROCTRAN_DATE));
        row.setTime(now.format(PROCTRAN_TIME));

        String type;
        String desc;
        if (amt.signum() < 0) {
            type = "DEB";
            desc = "COUNTER WTHDRW";
            if (origin.facilType() == FACILTYPE_PAYMENT) {
                type = "PDR";
                desc = origin.commareaPrefix(14);
            }
        } else {
            type = "CRE";
            desc = "COUNTER RECVED";
            if (origin.facilType() == FACILTYPE_PAYMENT) {
                type = "PCR";
                desc = origin.commareaPrefix(14);
            }
        }
        row.setType(type);
        // L489: MOVE SPACES TO HV-PROCTRAN-DESC (PIC X(40)) before the MOVE.
        row.setDesc(CobolText.moveToPicX(desc, 40));
        row.setAmount(amt);

        try {
            procTranRepository.saveAndFlush(row);
        } catch (DataAccessException e) {
            throw new ProctranWriteException(e);
        }
    }

    /** CHECK-FOR-STORM-DRAIN-DB2 (L668-692). */
    static boolean checkForStormDrain(DataAccessException e) {
        if (isConnectionLost(e)) {
            LOG.warn("DBCRFUN: Check-For-Storm-Drain-DB2: Storm Drain condition (DB2 Connection lost) has been met ({}).",
                    e.getMessage());
            return true;
        }
        return false;
    }

    /** Equivalent of SQLCODE 923: a lost connection, or a JDBC error with SQLCODE +/-923 or SQLSTATE class 08. */
    static boolean isConnectionLost(DataAccessException e) {
        if (e instanceof DataAccessResourceFailureException) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (Math.abs(sql.getErrorCode()) == SQLCODE_CONNECTION_LOST
                        || (state != null && state.startsWith("08"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void requestRollback() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    private static DbcrfunResponse failure(String sortcode, String failCode) {
        return new DbcrfunResponse(SUCCESS_NO, failCode, sortcode, null, null);
    }
}
