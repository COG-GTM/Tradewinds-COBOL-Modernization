package com.ibm.cics.cip.bank.core.updacc;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of COBOL program UPDACC: updates the type, interest rate and overdraft limit of an account.
 * Balances and dates are never changed and no PROCTRAN row is written.
 *
 * <ul>
 *   <li>{@code PREMIERE / A010}: the sort code always comes from SORTCODE.cpy (the COMMAREA value is ignored).</li>
 *   <li>{@code UPDATE-ACCOUNT-DB2 / UAD010}: SELECT the row ({@code SQLCODE NOT = 0} -> not found), reject a blank
 *       account type ({@code COMM-ACC-TYPE = SPACES OR COMM-ACC-TYPE(1:1) = ' '}), UPDATE the three columns and
 *       return the row with the new values.</li>
 * </ul>
 */
@Service
public class UpdateAccountService {

    /** COMM-INT-RATE PIC 9(4)V99. */
    static final BigDecimal MAX_INTEREST_RATE = new BigDecimal("9999.99");
    /** COMM-OVERDRAFT PIC 9(8). */
    static final int MAX_OVERDRAFT = 99_999_999;
    /** COMM-ACC-TYPE PIC X(8). */
    static final int ACCOUNT_TYPE_LENGTH = 8;

    private final AccountRepository accounts;
    private final BankIdentity bank;

    public UpdateAccountService(AccountRepository accounts, BankIdentity bank) {
        this.accounts = accounts;
        this.bank = bank;
    }

    /**
     * UPDACC {@code PREMIERE} + {@code UPDATE-ACCOUNT-DB2}.
     *
     * @return the account as stored after the update (COMMAREA out with {@code COMM-SUCCESS = 'Y'})
     * @throws CbsaException 404 when the account does not exist, 400 when the type is blank or a value does not fit
     *         its COMMAREA field, 500 when the UPDATE affects no row
     */
    @Transactional
    public Account updateAccount(long accountNumber, String accountType, BigDecimal interestRate, int overdraftLimit) {
        String sortCode = bank.sortCode();

        // UAD010: SELECT ... WHERE ACCOUNT_SORTCODE = :sortcode AND ACCOUNT_NUMBER = :accno
        Account current = accounts.findByKey(sortCode, accountNumber)
                .orElseThrow(() -> CbsaException.notFound("Account number " + accountNumber + " not found."));

        // IF (COMM-ACC-TYPE = SPACES OR COMM-ACC-TYPE(1:1) = ' ')
        if (accountType == null || accountType.isEmpty() || accountType.charAt(0) == ' ') {
            throw CbsaException.badRequest("Account type must not be blank.");
        }

        // MOVE COMM-ACC-TYPE / COMM-OVERDRAFT / COMM-INT-RATE TO HV-ACCOUNT-*
        String newType = commAccType(accountType);
        BigDecimal newRate = commIntRate(interestRate);
        int newOverdraft = commOverdraft(overdraftLimit);

        // UPDATE ACCOUNT SET ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OVERDRAFT_LIMIT
        int rows = accounts.updateTypeRateOverdraft(sortCode, accountNumber, newType, newRate, newOverdraft);
        if (rows != 1) {
            throw CbsaException.serverError("Failed to update account number " + accountNumber + ".", null);
        }

        // MOVE HV-ACCOUNT-* TO COMM-*: re-read so balances reflect any change committed since UAD010
        return accounts.findByKey(sortCode, accountNumber)
                .orElseGet(() -> current.withTypeRateOverdraft(newType, newRate, newOverdraft));
    }

    /** MOVE to PIC X(8): truncated to 8 characters; CHAR(8) trailing blanks are not significant. */
    private static String commAccType(String accountType) {
        String v = accountType.length() > ACCOUNT_TYPE_LENGTH ? accountType.substring(0, ACCOUNT_TYPE_LENGTH)
                : accountType;
        return v.stripTrailing();
    }

    /** PIC 9(4)V99: unsigned, at most 9999.99; extra decimals are truncated as a COBOL MOVE does. */
    private static BigDecimal commIntRate(BigDecimal interestRate) {
        if (interestRate == null) {
            throw CbsaException.badRequest("Interest rate must be supplied.");
        }
        if (interestRate.signum() < 0) {
            throw CbsaException.badRequest("Interest rate cannot be less than zero.");
        }
        if (interestRate.compareTo(MAX_INTEREST_RATE) > 0) {
            throw CbsaException.badRequest("Interest rate cannot be greater than 9999.99%.");
        }
        return interestRate.setScale(2, RoundingMode.DOWN);
    }

    /** PIC 9(8): unsigned, at most 99999999. */
    private static int commOverdraft(int overdraftLimit) {
        if (overdraftLimit < 0) {
            throw CbsaException.badRequest("Overdraft limit cannot be less than zero.");
        }
        if (overdraftLimit > MAX_OVERDRAFT) {
            throw CbsaException.badRequest("Overdraft limit cannot be greater than " + MAX_OVERDRAFT + ".");
        }
        return overdraftLimit;
    }
}
