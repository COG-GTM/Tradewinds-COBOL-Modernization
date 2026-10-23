package com.ibm.cics.cip.bank.core.crecust;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.proctran.TaskNumberSupplier;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Port of CRECUST {@code PREMIERE / P010}, in the legacy order:
 * <ol>
 * <li>title check (R-C1, fail code {@code T});</li>
 * <li>{@code POPULATE-TIME-DATE};</li>
 * <li>{@code CREDIT-CHECK} (R-C3, fail code {@code G} / {@code F}) - runs before the date-of-birth check;</li>
 * <li>{@code DATE-OF-BIRTH-CHECK} (R-C2, fail codes {@code O} / {@code Y} / {@code Z});</li>
 * <li>{@code ENQ-NAMED-COUNTER}, {@code UPD-NCS}, {@code WRITE-CUSTOMER-VSAM}, {@code WRITE-PROCTRAN-DB2}
 * ({@link CustomerWriter}, one transaction).</li>
 * </ol>
 * Name and address are moved into COMM-NAME PIC X(60) / COMM-ADDRESS PIC X(160) (truncated).
 */
@Service
public class CreateCustomerService {

    static final int NAME_WIDTH = 60;
    static final int ADDRESS_WIDTH = 160;

    private final TitleValidator titles;
    private final CreditCheckService creditCheck;
    private final CustomerWriter writer;
    private final TaskNumberSupplier taskNumbers;
    private final BankIdentity bank;
    private final Clock clock;

    public CreateCustomerService(TitleValidator titles, CreditCheckService creditCheck, CustomerWriter writer,
            TaskNumberSupplier taskNumbers, BankIdentity bank, Clock clock) {
        this.titles = titles;
        this.creditCheck = creditCheck;
        this.writer = writer;
        this.taskNumbers = taskNumbers;
        this.bank = bank;
        this.clock = clock;
    }

    public Customer createCustomer(String name, String address, LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            throw CbsaException.badRequest("Date of Birth is null");
        }
        return createCustomer(name, address, dateOfBirth.getYear(), dateOfBirth.getMonthValue(),
                dateOfBirth.getDayOfMonth());
    }

    /**
     * Variant taking the COMM-DOB-GROUP parts unvalidated, so that impossible dates (month 13) reach
     * DATE-OF-BIRTH-CHECK and fail with {@code Z} after the credit check, as in CRECUST.
     */
    public Customer createCustomer(String name, String address, int birthYear, int birthMonth, int birthDay) {
        if (name == null) {
            throw CbsaException.badRequest("Customer name is null");
        }
        if (address == null) {
            throw CbsaException.badRequest("Customer address is null");
        }
        String commName = commField(name, NAME_WIDTH);
        String commAddress = commField(address, ADDRESS_WIDTH);

        if (!titles.isValid(commName)) {
            throw CbsaException.badRequest("Customer title " + TitleValidator.firstWord(commName) + " is not valid",
                    "T");
        }

        long taskNumber = taskNumbers.nextTaskNumber();
        LocalDate today = LocalDate.now(clock);

        Customer applicant = new Customer(Customer.EYECATCHER, bank.sortCode(), 0L, commName, commAddress,
                validDate(birthYear, birthMonth, birthDay).orElse(null), 0, null);
        CreditCheckResult credit = creditCheck.check(applicant, taskNumber);
        if (!credit.ok()) {
            throw CbsaException.serverError(CreditCheckResult.FAIL_AGENCY_ABEND.equals(credit.failCode())
                    ? "Credit check failed: a credit agency abended"
                    : "Credit check failed: no credit agency responded", credit.failCode());
        }

        DateOfBirthValidator.check(birthYear, birthMonth, birthDay, today).ifPresent(f -> {
            throw CbsaException.badRequest(f.message(), f.failCode());
        });
        LocalDate dateOfBirth = LocalDate.of(birthYear, birthMonth, birthDay);

        Clock taskClock = creditCheck.isSimulatingDelay() ? clock : Clock.offset(clock, CreditCheckService.FETCH_WINDOW);
        return writer.write(bank.sortCode(), commName, commAddress, dateOfBirth, credit, taskClock);
    }

    /** MOVE into PIC X(n): truncate; trailing blanks are not stored. */
    static String commField(String value, int width) {
        return CbsaFormats.fixed(value, width).stripTrailing();
    }

    private static Optional<LocalDate> validDate(int year, int month, int day) {
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
    }
}
