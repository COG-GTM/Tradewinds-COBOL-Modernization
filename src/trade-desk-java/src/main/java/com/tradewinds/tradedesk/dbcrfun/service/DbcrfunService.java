package com.tradewinds.tradedesk.dbcrfun.service;

import com.tradewinds.tradedesk.dbcrfun.dto.DbcrfunRequest;
import com.tradewinds.tradedesk.dbcrfun.dto.DbcrfunResponse;
import com.tradewinds.tradedesk.dbcrfun.dto.Origin;
import com.tradewinds.tradedesk.dbcrfun.entity.Account;
import com.tradewinds.tradedesk.dbcrfun.entity.ProcTran;
import com.tradewinds.tradedesk.dbcrfun.repository.AccountRepository;
import com.tradewinds.tradedesk.dbcrfun.repository.ProcTranRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Port of DBCRFUN.cbl (UPDATE-ACCOUNT-DB2 + WRITE-TO-PROCTRAN-DB2). */
@Service
public class DbcrfunService {

    static final int PAYMENT_FACILTYPE = 496;

    static final String FAIL_NOT_FOUND = "1";
    static final String FAIL_DATA_ACCESS = "2";
    static final String FAIL_INSUFFICIENT_FUNDS = "3";
    static final String FAIL_RESTRICTED_ACCOUNT = "4";
    static final String FAIL_PROCTRAN = "02";

    static final String TYPE_TELLER_DEBIT = "DEB";
    static final String TYPE_PAYMENT_DEBIT = "PDR";
    static final String TYPE_TELLER_CREDIT = "CRE";
    static final String TYPE_PAYMENT_CREDIT = "PCR";
    static final String DESC_TELLER_DEBIT = "COUNTER WTHDRW";
    static final String DESC_TELLER_CREDIT = "COUNTER RECVED";

    private static final String TYPE_HOSPITAL = "HOSPITAL";
    private static final String TYPE_TENDER = "TENDER  ";
    private static final int ACCOUNT_TYPE_LENGTH = 8;
    private static final int ACCOUNT_NUMBER_LENGTH = 8;
    private static final int ORIGIN_FIELD_LENGTH = 8;
    private static final int ORIGIN_DESC_LENGTH = 14;
    private static final int AMOUNT_SCALE = 2;

    private static final DateTimeFormatter PROCTRAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter PROCTRAN_TIME = DateTimeFormatter.ofPattern("HHmmss");

    private static final Logger LOG = LoggerFactory.getLogger(DbcrfunService.class);

    private final AccountRepository accountRepository;
    private final ProcTranRepository procTranRepository;
    private final TransactionRollback transactionRollback;
    private final ProcTranReferenceGenerator referenceGenerator;
    private final Clock clock;
    private final String sortcode;

    public DbcrfunService(
            AccountRepository accountRepository,
            ProcTranRepository procTranRepository,
            TransactionRollback transactionRollback,
            ProcTranReferenceGenerator referenceGenerator,
            Clock clock,
            @Value("${dbcrfun.sortcode:987654}") String sortcode) {
        this.accountRepository = accountRepository;
        this.procTranRepository = procTranRepository;
        this.transactionRollback = transactionRollback;
        this.referenceGenerator = referenceGenerator;
        this.clock = clock;
        this.sortcode = sortcode;
    }

    /**
     * Applies {@code amt} to the account and records a PROCTRAN row. Like the COBOL, the
     * request sort code is ignored: the bank's own sort code (SORTCODE.cpy) is used.
     */
    @Transactional
    public DbcrfunResponse process(DbcrfunRequest request) {
        BigDecimal amt = request.amt().setScale(AMOUNT_SCALE, RoundingMode.DOWN);
        Origin origin = request.origin();
        int faciltype = origin == null ? 0 : origin.faciltype();
        boolean payment = faciltype == PAYMENT_FACILTYPE;
        String accno = toAccountNumber(request.accno());

        Optional<Account> found;
        try {
            found = accountRepository.findForUpdate(sortcode, accno);
        } catch (DataAccessException e) {
            LOG.error("DBCRFUN: ACCOUNT select failed", e);
            transactionRollback.markRollbackOnly();
            return DbcrfunResponse.failure(FAIL_DATA_ACCESS);
        }
        if (found.isEmpty()) {
            return DbcrfunResponse.failure(FAIL_NOT_FOUND);
        }
        Account account = found.get();
        if (account.getAvailableBalance() == null || account.getActualBalance() == null) {
            LOG.error("DBCRFUN: ACCOUNT row has a NULL balance");
            return DbcrfunResponse.failure(FAIL_DATA_ACCESS);
        }

        if (amt.signum() < 0) {
            if (payment && isRestrictedAccountType(account.getAccountType())) {
                return DbcrfunResponse.failure(FAIL_RESTRICTED_ACCOUNT);
            }
            BigDecimal difference = account.getAvailableBalance().add(amt);
            if (difference.signum() < 0 && payment) {
                return DbcrfunResponse.failure(FAIL_INSUFFICIENT_FUNDS);
            }
        }

        // DBCRFUN repeats this check unconditionally, so it also blocks payment credits.
        if (payment && isRestrictedAccountType(account.getAccountType())) {
            return DbcrfunResponse.failure(FAIL_RESTRICTED_ACCOUNT);
        }

        BigDecimal newAvailable = account.getAvailableBalance().add(amt);
        BigDecimal newActual = account.getActualBalance().add(amt);
        account.setAvailableBalance(newAvailable);
        account.setActualBalance(newActual);
        try {
            accountRepository.saveAndFlush(account);
        } catch (DataAccessException e) {
            LOG.error("DBCRFUN: ACCOUNT update failed", e);
            transactionRollback.markRollbackOnly();
            return DbcrfunResponse.failure(FAIL_DATA_ACCESS, newAvailable, newActual);
        }

        try {
            procTranRepository.saveAndFlush(buildProcTran(accno, amt, origin, payment));
        } catch (DataAccessException e) {
            LOG.error("DBCRFUN: unable to write to PROCTRAN, rolling back", e);
            transactionRollback.markRollbackOnly();
            return DbcrfunResponse.failure(FAIL_PROCTRAN, newAvailable, newActual);
        }

        return DbcrfunResponse.success(newAvailable, newActual);
    }

    private ProcTran buildProcTran(String accno, BigDecimal amt, Origin origin, boolean payment) {
        LocalDateTime now = LocalDateTime.now(clock);
        ProcTran tran = new ProcTran();
        tran.setEyecatcher(ProcTran.EYECATCHER);
        tran.setSortcode(sortcode);
        tran.setNumber(accno);
        tran.setRef(referenceGenerator.next());
        tran.setDate(now.format(PROCTRAN_DATE));
        tran.setTime(now.format(PROCTRAN_TIME));
        if (amt.signum() < 0) {
            tran.setType(payment ? TYPE_PAYMENT_DEBIT : TYPE_TELLER_DEBIT);
            tran.setDesc(payment ? originDescription(origin) : DESC_TELLER_DEBIT);
        } else {
            tran.setType(payment ? TYPE_PAYMENT_CREDIT : TYPE_TELLER_CREDIT);
            tran.setDesc(payment ? originDescription(origin) : DESC_TELLER_CREDIT);
        }
        tran.setAmount(amt);
        return tran;
    }

    /** COMM-ORIGIN(1:14): APPLID PIC X(8) followed by the first 6 bytes of USERID. */
    static String originDescription(Origin origin) {
        String applid = origin == null ? null : origin.applid();
        String userid = origin == null ? null : origin.userid();
        String group = picX(applid, ORIGIN_FIELD_LENGTH) + picX(userid, ORIGIN_FIELD_LENGTH);
        return group.substring(0, ORIGIN_DESC_LENGTH);
    }

    static boolean isRestrictedAccountType(String accountType) {
        String padded = picX(accountType, ACCOUNT_TYPE_LENGTH);
        return TYPE_HOSPITAL.equals(padded) || TYPE_TENDER.equals(padded);
    }

    /** MOVE COMM-ACCNO TO DESIRED-ACC-NO (PIC 9(8)): numeric values are zero-filled. */
    static String toAccountNumber(String accno) {
        if (accno == null) {
            return "";
        }
        String trimmed = accno.trim();
        if (!trimmed.isEmpty() && trimmed.length() <= ACCOUNT_NUMBER_LENGTH && trimmed.chars().allMatch(Character::isDigit)) {
            return "0".repeat(ACCOUNT_NUMBER_LENGTH - trimmed.length()) + trimmed;
        }
        return trimmed;
    }

    /** COBOL MOVE into PIC X(n): pad with spaces or truncate to n. */
    private static String picX(String value, int length) {
        String v = value == null ? "" : value;
        return v.length() >= length ? v.substring(0, length) : v + " ".repeat(length - v.length());
    }
}
