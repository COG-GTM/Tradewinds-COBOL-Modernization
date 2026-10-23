package com.ibm.cics.cip.bank.core.bankdata;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.config.CbsaProperties;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.domain.ControlRecord;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Port of batch program BANKDATA ({@code src/base/cobol_src/BANKDATA.cbl}): generates the CUSTOMER,
 * ACCOUNT and CONTROL test data for one sort code. Pure: it returns a {@link GeneratedBank};
 * {@link BankDataLoader} applies it to the database and {@link BankDataSqlWriter} renders seed.sql.
 *
 * <h2>Algorithm (paragraph {@code PREMIERE / A010})</h2>
 * <ol>
 * <li>{@code UNSTRING PARM ... INTO START-KEY END-KEY STEP-KEY RANDOM-SEED}; RC 12 if END-KEY &lt; START-KEY
 *     (checked first), then RC 12 if STEP-KEY = 0.</li>
 * <li>{@code GET-TODAYS-DATE}: today from the {@link Clock} ({@code cbsa.clock.fixed}).</li>
 * <li>{@code FUNCTION RANDOM(RANDOM-SEED-NUMERIC)} once and six more {@code FUNCTION RANDOM}s for the
 *     (unused) initial table pointers. RANDOM-SEED is {@code PIC X(16)} redefined as {@code PIC 9(16)}: the
 *     seed text is left-justified and space-filled, and the harness reads the spaces as zeros, so PARM seed
 *     {@code 42} is the number {@code 4200000000000000} (see {@link #randomSeedNumeric(long)}). The harness
 *     adds {@code CBSA_RANDOM_SEED} ({@code cbsa.random.seed}, default 0) to it.</li>
 * <li>For each NEXT-KEY from START-KEY by STEP-KEY until &gt; END-KEY, 15 RANDOMs in this order: title (1-35),
 *     forename (1-49), initial (1-29), surname (1-49), house number (1-99, PIC 99), street tree (1-25), street
 *     road (1-18), town (1-49), birth day (1-27), month (1-11), year (1900-1999), credit score (1-998),
 *     review-date offset (1-20 days after today); then {@code DEFINE-ACC}.</li>
 * <li>{@code DEFINE-ACC}: number of accounts 1-4; {@code POPULATE-ACC} for WS-CNT = 1..n uses account type
 *     / interest rate / overdraft WS-CNT (ISA, SAVING, CURRENT, LOAN - never MORTGAGE), runs
 *     {@code GENERATE-OPENED-DATE} until valid, then one RANDOM for the balance (1.00-999998.99, negated
 *     for LOAN/MORTGAGE). Account numbers start at 1 (WS-ACCOUNT-NUMBER VALUE 1) and increase by 1.</li>
 * <li>Control rows: ACCOUNT-LAST = LAST-ACCOUNT-NUMBER, ACCOUNT-COUNT = NUMBER-OF-ACCOUNTS (DB2 CONTROL),
 *     CUSTOMER-COUNT / CUSTOMER-LAST from the customer-file control record (NUMBER-OF-CUSTOMERS /
 *     LAST-CUSTOMER-NUMBER), which legacy/tools/make_seed.py stores in CONTROL.</li>
 * </ol>
 *
 * <h2>baseline/seed/seed.sql</h2>
 * Produced by {@code legacy/make-seed.sh}: {@code PARM='1,30,1,42'}, {@code CBSA_RANDOM_SEED=42},
 * {@code CBSA_FIXED_TIME=2026-01-15T10:30:00Z}, i.e. {@code generate(1, 30, 1, 42)} with
 * {@code cbsa.random.seed=42} and {@code cbsa.clock.fixed=2026-01-15T10:30:00Z}, followed by the seed
 * activity in {@code legacy/cases/seed-activity.json} (CREACC x3, DBCRFUN x4, XFRFUN x1).
 */
@Component
public class BankDataGenerator {

    static final LocalDate LAST_STATEMENT = LocalDate.of(2021, 7, 1);
    static final LocalDate NEXT_STATEMENT = LocalDate.of(2021, 8, 1);
    static final long MAX_KEY = 9_999_999_999L;
    static final int MAX_OPENED_DATE_ATTEMPTS = 100;

    private final String sortCode;
    private final Clock clock;
    private final long randomBase;

    @Autowired
    public BankDataGenerator(BankIdentity bankIdentity, Clock clock, CbsaProperties properties) {
        this(bankIdentity.sortCode(), clock,
                properties.random() == null || properties.random().seed() == null ? 0L
                        : properties.random().seed());
    }

    /**
     * @param randomBase legacy {@code CBSA_RANDOM_SEED} / {@code cbsa.random.seed} (0 when unset)
     */
    public BankDataGenerator(String sortCode, Clock clock, long randomBase) {
        this.sortCode = sortCode;
        this.clock = clock;
        this.randomBase = randomBase;
    }

    /**
     * BANKDATA {@code PARM='startKey,endKey,stepKey,seed'}.
     *
     * @throws CbsaException 400 with fail code {@code 12} (BANKDATA RETURN-CODE) for invalid parameters
     */
    public GeneratedBank generate(long startKey, long endKey, long stepKey, long seed) {
        validate(startKey, endKey, stepKey, seed);
        LocalDate today = LocalDate.now(clock);

        BankDataRandom random = new BankDataRandom(randomBase + randomSeedNumeric(seed));
        // FORENAMES-PTR, INITIALS-PTR, SURNAMES-PTR, TOWN-PTR, STREET-NAME-R-PTR, STREET-NAME-T-PTR,
        // ACCOUNT-TYPES-PTR: computed in A010 and overwritten before use.
        for (int i = 0; i < 7; i++) {
            random.next9();
        }

        List<Customer> customers = new ArrayList<>();
        List<Account> accounts = new ArrayList<>();
        long accountNumber = 1;
        long lastCustomer = 0;
        long lastAccount = 0;
        for (long key = startKey; key <= endKey; key += stepKey) {
            Customer customer = generateCustomer(random, key, today);
            customers.add(customer);
            lastCustomer = key;
            for (Account account : defineAccounts(random, customer, accountNumber)) {
                accounts.add(account);
                lastAccount = account.accountNumber();
                accountNumber++;
            }
        }

        List<ControlRecord> controls = List.of(
                new ControlRecord(sortCode + "-ACCOUNT-COUNT", (long) accounts.size(), ""),
                new ControlRecord(sortCode + "-ACCOUNT-LAST", lastAccount, ""),
                new ControlRecord(sortCode + "-CUSTOMER-COUNT", (long) customers.size(), ""),
                new ControlRecord(sortCode + "-CUSTOMER-LAST", lastCustomer, ""));
        return new GeneratedBank(sortCode, customers, accounts, controls);
    }

    /** A010 PARM checks, in legacy order; key ranges are the PIC 9(10) limits of START/END/STEP-KEY. */
    private static void validate(long startKey, long endKey, long stepKey, long seed) {
        if (startKey < 0 || endKey < 0 || stepKey < 0 || startKey > MAX_KEY || endKey > MAX_KEY
                || stepKey > MAX_KEY) {
            throw new CbsaException(HttpStatus.BAD_REQUEST,
                    "Customer numbers and gap must be between 0 and " + MAX_KEY, "12");
        }
        if (seed < 0) {
            throw new CbsaException(HttpStatus.BAD_REQUEST, "Random seed must not be negative", "12");
        }
        if (endKey < startKey) {
            throw new CbsaException(HttpStatus.BAD_REQUEST,
                    "Final customer number cannot be smaller than first customer number", "12");
        }
        if (stepKey == 0) {
            throw new CbsaException(HttpStatus.BAD_REQUEST, "Gap between customers cannot be zero", "12");
        }
    }

    /**
     * {@code RANDOM-SEED PIC X(16)} redefined as {@code PIC 9(16)}: the PARM text is left-justified and the
     * trailing spaces read as zeros, e.g. 42 -&gt; 4200000000000000. Text longer than 16 digits is truncated.
     */
    static long randomSeedNumeric(long seed) {
        String text = Long.toString(seed);
        if (text.length() > 16) {
            text = text.substring(0, 16);
        }
        long value = Long.parseLong(text);
        for (int i = text.length(); i < 16; i++) {
            value *= 10;
        }
        return value;
    }

    /** Customer part of the A010 PERFORM VARYING NEXT-KEY loop (name, address, DOB, credit score). */
    private Customer generateCustomer(BankDataRandom random, long key, LocalDate today) {
        int title = (int) random.scaled(36 - 1, 1);
        int forename = (int) random.scaled(BankDataTables.FORENAMES.size() - 1, 1);
        int initial = (int) random.scaled(BankDataTables.INITIALS.length() - 1, 1);
        int surname = (int) random.scaled(BankDataTables.SURNAMES.size() - 1, 1);
        int houseNumber = (int) random.scaled(99, 1) % 100; // PIC 99
        int tree = (int) random.scaled(BankDataTables.STREET_TREES.size() - 1, 1);
        int road = (int) random.scaled(BankDataTables.STREET_ROADS.size() - 1, 1);
        int town = (int) random.scaled(BankDataTables.TOWNS.size() - 1, 1);

        // STRING ... DELIMITED BY SPACE (title, forename, initial) / BY SIZE (surname, trailing spaces trimmed)
        String name = delimitedBySpace(BankDataTables.TITLES.get(title - 1)) + " "
                + delimitedBySpace(BankDataTables.FORENAMES.get(forename - 1)) + " "
                + delimitedBySpace(String.valueOf(BankDataTables.INITIALS.charAt(initial - 1))) + " "
                + BankDataTables.SURNAMES.get(surname - 1);
        String address = String.format("%02d", houseNumber) + " "
                + delimitedBySpace(BankDataTables.STREET_TREES.get(tree - 1)) + " "
                + delimitedBySpace(BankDataTables.STREET_ROADS.get(road - 1)) + ", "
                + delimitedBySpace(BankDataTables.TOWNS.get(town - 1));

        int birthDay = (int) random.scaled(28 - 1, 1);
        int birthMonth = (int) random.scaled(12 - 1, 1);
        int birthYear = (int) random.scaled(2000 - 1900, 1900);
        int creditScore = (int) random.scaled(999 - 1, 1);
        int reviewDateAdd = (int) random.scaled(21 - 1, 1);

        return new Customer(Customer.EYECATCHER, sortCode, key, name.stripTrailing(), address.stripTrailing(),
                LocalDate.of(birthYear, birthMonth, birthDay), creditScore, today.plusDays(reviewDateAdd));
    }

    /** {@code DEFINE-ACC} + {@code POPULATE-ACC}. */
    private List<Account> defineAccounts(BankDataRandom random, Customer customer, long firstAccountNumber) {
        int noOfAccounts = (int) random.scaled(5 - 1, 1);
        List<Account> result = new ArrayList<>(noOfAccounts);
        for (int cnt = 1; cnt <= noOfAccounts; cnt++) {
            LocalDate opened = generateOpenedDate(random, customer.dateOfBirth());
            String type = BankDataTables.ACCOUNT_TYPES.get(cnt - 1);
            // HV-ACCOUNT-AVAILABLE-BALANCE PIC S9(10)V99 = ((999999 - 1) * RANDOM) + 1, truncated to cents
            long cents = Math.floorDiv((999_999L - 1) * random.next9(), BankDataRandom.SCALE / 100) + 100;
            BigDecimal balance = BigDecimal.valueOf(cents, 2);
            if ("LOAN".equals(type) || "MORTGAGE".equals(type)) {
                balance = balance.negate();
            }
            result.add(new Account(Account.EYECATCHER, customer.customerNumber(), sortCode,
                    firstAccountNumber + cnt - 1, type, BankDataTables.INTEREST_RATES.get(cnt - 1), opened,
                    BankDataTables.OVERDRAFT_LIMITS.get(cnt - 1), LAST_STATEMENT, NEXT_STATEMENT, balance,
                    balance));
        }
        return result;
    }

    /**
     * {@code PERFORM GENERATE-OPENED-DATE UNTIL OPENED-DATE-VALID = 'Y'} (paragraph GOD010): three RANDOMs
     * per attempt (day 1-27, month 1-11, year); valid when year &gt; birth year, else after more than 100
     * attempts the date of birth is used.
     *
     * <p>Year is {@code COMPUTE WS-ACCOUNT-OPENED-YEAR = ((2014 - CUSTOMER-BIRTH-YEAR) * RANDOM) +
     * CUSTOMER-BIRTH-YEAR}. Under the legacy harness (GnuCOBOL 3.1.2, {@code cobc -std=ibm}) the product
     * {@code (2014 - CUSTOMER-BIRTH-YEAR) * CBSA-RND-VAL} evaluates to 0 (reproduced with a minimal program;
     * without {@code -std=ibm} it is computed correctly), so the year always equals the birth year, the test
     * never passes, and every account is opened on the customer's date of birth after 101 attempts (303
     * RANDOMs). This port reproduces the harness (and therefore baseline/seed/seed.sql); see
     * {@link #openedYearOffset(long, int)}.
     */
    private static LocalDate generateOpenedDate(BankDataRandom random, LocalDate dateOfBirth) {
        int birthYear = dateOfBirth.getYear();
        int attempts = 0;
        while (true) {
            int day = (int) random.scaled(28 - 1, 1);
            int month = (int) random.scaled(12 - 1, 1);
            int year = (int) (openedYearOffset(random.next9(), birthYear) + birthYear);
            attempts++;
            if (year > birthYear) {
                return LocalDate.of(year, month, day);
            }
            if (attempts > MAX_OPENED_DATE_ATTEMPTS) {
                return dateOfBirth;
            }
        }
    }

    /**
     * {@code (2014 - CUSTOMER-BIRTH-YEAR) * FUNCTION RANDOM} as evaluated by the GnuCOBOL {@code -std=ibm}
     * harness that produced the baseline: always 0 (see {@link #generateOpenedDate}). The RANDOM value is
     * still consumed.
     */
    static long openedYearOffset(long random9, int birthYear) {
        return 0L;
    }

    private static String delimitedBySpace(String value) {
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(0, space);
    }
}
