package com.ibm.cics.cip.bank.core.common.proctran;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.domain.ProcessedTransaction;
import com.ibm.cics.cip.bank.core.common.domain.ProctranType;
import com.ibm.cics.cip.bank.core.common.repository.ProctranRepository;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.stereotype.Component;

/**
 * Writes PROCTRAN audit rows with the exact PROC-TRAN-DESC layouts of PROCTRAN.cpy, as built by
 * the WRITE-PROCTRAN-DB2 paragraphs of each COBOL program. Date/time come from the shared Clock.
 */
@Component
public class ProctranWriter {

    public static final int DESC_WIDTH = 40;
    private static final String ZERO_ACCOUNT = "00000000";

    private final ProctranRepository repository;
    private final TaskNumberSupplier taskNumbers;
    private final Clock clock;

    public ProctranWriter(ProctranRepository repository, TaskNumberSupplier taskNumbers, Clock clock) {
        this.repository = repository;
        this.taskNumbers = taskNumbers;
        this.clock = clock;
    }

    /** Generic write; {@code number} is the 8-char PROCTRAN_NUMBER, {@code description} is padded to 40. */
    public ProcessedTransaction write(ProctranType type, String sortCode, String number, String description,
            BigDecimal amount) {
        ProcessedTransaction row = new ProcessedTransaction(
                ProcessedTransaction.EYECATCHER,
                sortCode,
                number,
                LocalDate.now(clock),
                CbsaFormats.HHMMSS.format(LocalTime.now(clock)),
                taskNumbers.nextReference(),
                type.code(),
                CbsaFormats.fixed(description, DESC_WIDTH),
                CbsaFormats.money(amount == null ? BigDecimal.ZERO : amount));
        repository.insert(row);
        return row;
    }

    /**
     * DBCRFUN: DEB 'COUNTER WTHDRW' / CRE 'COUNTER RECVED'; for payments (COMM-FACILTYPE = 496) PDR / PCR
     * with COMM-ORIGIN(1:14) (applid(8) + userid(1:6)) as description. Sign of {@code amount} selects debit.
     */
    public ProcessedTransaction debitCredit(String sortCode, long accountNumber, BigDecimal amount, boolean payment,
            String origin14) {
        boolean debit = amount.signum() < 0;
        ProctranType type;
        String desc;
        if (payment) {
            type = debit ? ProctranType.PAYMENT_DEBIT : ProctranType.PAYMENT_CREDIT;
            desc = CbsaFormats.fixed(origin14, 14);
        } else {
            type = debit ? ProctranType.DEBIT : ProctranType.CREDIT;
            desc = debit ? "COUNTER WTHDRW" : "COUNTER RECVED";
        }
        return write(type, sortCode, CbsaFormats.accountNumber(accountNumber), desc, amount);
    }

    /** XFRFUN: TFR on the FROM account; desc = 'TRANSFER' (26) + to-sortcode(6) + to-account(8). */
    public ProcessedTransaction transfer(String fromSortCode, long fromAccount, String toSortCode, long toAccount,
            BigDecimal amount) {
        String desc = CbsaFormats.fixed("TRANSFER", 26) + CbsaFormats.fixed(toSortCode, 6)
                + CbsaFormats.accountNumber(toAccount);
        return write(ProctranType.TRANSFER, fromSortCode, CbsaFormats.accountNumber(fromAccount), desc, amount);
    }

    /** CRECUST (OCC) / web create (ICC): number '00000000', desc sortcode(6)+custno(10)+name(14)+dob dd/mm/yyyy. */
    public ProcessedTransaction createCustomer(ProctranType type, Customer c) {
        return write(type, c.sortCode(), ZERO_ACCOUNT, customerDescription(c), BigDecimal.ZERO);
    }

    /** DELCUS (ODC) / web delete (IDC): same layout as create. */
    public ProcessedTransaction deleteCustomer(ProctranType type, Customer c) {
        return write(type, c.sortCode(), ZERO_ACCOUNT, customerDescription(c), BigDecimal.ZERO);
    }

    /**
     * CREACC (OCA/ICA): desc custno(10)+type(8)+last-stmt ddmmyyyy(8)+next-stmt ddmmyyyy(8)+6 spaces
     * (CREACC moves SPACES to DESC(35:6)).
     */
    public ProcessedTransaction createAccount(ProctranType type, Account a, BigDecimal amount) {
        return write(type, a.sortCode(), CbsaFormats.accountNumber(a.accountNumber()), accountDescription(a, "      "),
                amount);
    }

    /** DELACC (ODA/IDA): same layout as create but footer 'DELETE'; amount = actual balance. */
    public ProcessedTransaction deleteAccount(ProctranType type, Account a) {
        return write(type, a.sortCode(), CbsaFormats.accountNumber(a.accountNumber()), accountDescription(a, "DELETE"),
                a.actualBalance());
    }

    static String customerDescription(Customer c) {
        String dob = c.dateOfBirth() == null ? "00/00/0000" : CbsaFormats.DD_MM_YYYY_SLASH.format(c.dateOfBirth());
        return CbsaFormats.fixed(c.sortCode(), 6) + CbsaFormats.customerNumber(c.customerNumber())
                + CbsaFormats.fixed(c.name(), 14) + dob;
    }

    static String accountDescription(Account a, String footer) {
        return CbsaFormats.customerNumber(a.customerNumber()) + CbsaFormats.fixed(a.accountType(), 8)
                + CbsaFormats.ddmmyyyy(a.lastStatement()) + CbsaFormats.ddmmyyyy(a.nextStatement())
                + CbsaFormats.fixed(footer, 6);
    }
}
