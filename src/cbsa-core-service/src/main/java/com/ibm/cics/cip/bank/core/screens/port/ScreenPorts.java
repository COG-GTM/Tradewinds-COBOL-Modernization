package com.ibm.cics.cip.bank.core.screens.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import com.ibm.cics.cip.bank.core.common.repository.CustomerRepository;

/**
 * Resolves the business-service ports used by the screens. When no adapter bean is present (the wave-1
 * services are wired by the integration agent), inquiries fall back to the shared repositories and
 * updates fail the way an unavailable LINK target does in CICS: abend HBNK in the calling program.
 */
@Component
public class ScreenPorts {

    private final ObjectProvider<CustomerScreenPort> customerPort;
    private final ObjectProvider<AccountScreenPort> accountPort;
    private final ObjectProvider<PaymentScreenPort> paymentPort;
    private final RepositoryFallback fallback;

    public ScreenPorts(ObjectProvider<CustomerScreenPort> customerPort, ObjectProvider<AccountScreenPort> accountPort,
            ObjectProvider<PaymentScreenPort> paymentPort, CustomerRepository customers, AccountRepository accounts,
            BankIdentity bank) {
        this.customerPort = customerPort;
        this.accountPort = accountPort;
        this.paymentPort = paymentPort;
        this.fallback = new RepositoryFallback(customers, accounts, bank);
    }

    public CustomerScreenPort customers() {
        return customerPort.getIfAvailable(() -> fallback);
    }

    public AccountScreenPort accounts() {
        return accountPort.getIfAvailable(() -> fallback);
    }

    public PaymentScreenPort payments() {
        return paymentPort.getIfAvailable(() -> fallback);
    }

    /** Read-only fallback on the shared repositories; mutating LINKs abend. */
    static final class RepositoryFallback implements CustomerScreenPort, AccountScreenPort, PaymentScreenPort {
        private final CustomerRepository customers;
        private final AccountRepository accounts;
        private final BankIdentity bank;

        RepositoryFallback(CustomerRepository customers, AccountRepository accounts, BankIdentity bank) {
            this.customers = customers;
            this.accounts = accounts;
            this.bank = bank;
        }

        private static CbsaAbendException notWired(String program) {
            return new CbsaAbendException("HBNK", program, program + " service is not wired into the screen layer");
        }

        @Override
        public Optional<Customer> inquireCustomer(long customerNumber) {
            return customers.findByKey(bank.sortCode(), customerNumber);
        }

        @Override
        public Customer createCustomer(String name, String address, LocalDate dateOfBirth) {
            throw notWired("CRECUST");
        }

        @Override
        public Customer updateCustomer(long customerNumber, String name, String address) {
            throw notWired("UPDCUST");
        }

        @Override
        public Customer deleteCustomer(long customerNumber) {
            throw notWired("DELCUS");
        }

        @Override
        public Optional<Account> inquireAccount(long accountNumber) {
            return accounts.findByKey(bank.sortCode(), accountNumber);
        }

        @Override
        public List<Account> accountsForCustomer(long customerNumber) {
            if (!customers.exists(bank.sortCode(), customerNumber)) {
                throw new com.ibm.cics.cip.bank.core.common.error.CbsaException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Customer not found", "1");
            }
            return accounts.findByCustomer(bank.sortCode(), customerNumber);
        }

        @Override
        public Account createAccount(long customerNumber, String type, BigDecimal interestRate, int overdraftLimit,
                BigDecimal availableBalance, BigDecimal actualBalance) {
            throw notWired("CREACC");
        }

        @Override
        public Account updateAccount(long accountNumber, String type, BigDecimal interestRate, int overdraftLimit) {
            throw notWired("UPDACC");
        }

        @Override
        public Account deleteAccount(long accountNumber) {
            throw notWired("DELACC");
        }

        @Override
        public Account debitCredit(long accountNumber, BigDecimal amount, boolean payment, String origin14) {
            throw notWired("DBCRFUN");
        }

        @Override
        public Transfer transfer(long fromAccount, long toAccount, BigDecimal amount) {
            throw notWired("XFRFUN");
        }
    }
}
