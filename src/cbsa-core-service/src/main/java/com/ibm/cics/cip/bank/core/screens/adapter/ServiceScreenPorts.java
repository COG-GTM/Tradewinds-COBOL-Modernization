package com.ibm.cics.cip.bank.core.screens.adapter;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.creacc.CreateAccountService;
import com.ibm.cics.cip.bank.core.crecust.CreateCustomerService;
import com.ibm.cics.cip.bank.core.dbcrfun.DebitCreditService;
import com.ibm.cics.cip.bank.core.delacc.DeleteAccountService;
import com.ibm.cics.cip.bank.core.delcus.DeleteCustomerService;
import com.ibm.cics.cip.bank.core.inqacc.InquireAccountService;
import com.ibm.cics.cip.bank.core.inqcust.InquireCustomerService;
import com.ibm.cics.cip.bank.core.screens.port.AccountScreenPort;
import com.ibm.cics.cip.bank.core.screens.port.CustomerScreenPort;
import com.ibm.cics.cip.bank.core.screens.port.PaymentScreenPort;
import com.ibm.cics.cip.bank.core.updacc.UpdateAccountService;
import com.ibm.cics.cip.bank.core.updcust.UpdateCustomerService;
import com.ibm.cics.cip.bank.core.xfrfun.TransferResult;
import com.ibm.cics.cip.bank.core.xfrfun.TransferService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The LINK targets of the BMS screen flows: thin adapters from the screen ports to the business services.
 * Service failures surface as {@code CbsaException} carrying the legacy COMMAREA fail code.
 */
@Component
public class ServiceScreenPorts implements CustomerScreenPort, AccountScreenPort, PaymentScreenPort {

    private final InquireCustomerService inquireCustomer;
    private final CreateCustomerService createCustomer;
    private final UpdateCustomerService updateCustomer;
    private final DeleteCustomerService deleteCustomer;
    private final InquireAccountService inquireAccount;
    private final CreateAccountService createAccount;
    private final UpdateAccountService updateAccount;
    private final DeleteAccountService deleteAccount;
    private final DebitCreditService debitCredit;
    private final TransferService transfer;

    public ServiceScreenPorts(InquireCustomerService inquireCustomer, CreateCustomerService createCustomer,
            UpdateCustomerService updateCustomer, DeleteCustomerService deleteCustomer,
            InquireAccountService inquireAccount, CreateAccountService createAccount,
            UpdateAccountService updateAccount, DeleteAccountService deleteAccount, DebitCreditService debitCredit,
            TransferService transfer) {
        this.inquireCustomer = inquireCustomer;
        this.createCustomer = createCustomer;
        this.updateCustomer = updateCustomer;
        this.deleteCustomer = deleteCustomer;
        this.inquireAccount = inquireAccount;
        this.createAccount = createAccount;
        this.updateAccount = updateAccount;
        this.deleteAccount = deleteAccount;
        this.debitCredit = debitCredit;
        this.transfer = transfer;
    }

    @Override
    public Optional<Customer> inquireCustomer(long customerNumber) {
        return inquireCustomer.inquire(customerNumber);
    }

    @Override
    public Customer createCustomer(String name, String address, LocalDate dateOfBirth) {
        return createCustomer.createCustomer(name, address, dateOfBirth);
    }

    @Override
    public Customer updateCustomer(long customerNumber, String name, String address) {
        return updateCustomer.updateCustomer(customerNumber, name, address);
    }

    @Override
    public Customer deleteCustomer(long customerNumber) {
        return deleteCustomer.deleteCustomer(customerNumber);
    }

    @Override
    public Optional<Account> inquireAccount(long accountNumber) {
        return inquireAccount.inquire(accountNumber);
    }

    @Override
    public List<Account> accountsForCustomer(long customerNumber) {
        return inquireAccount.accountsForCustomer(customerNumber);
    }

    @Override
    public Account createAccount(long customerNumber, String type, BigDecimal interestRate, int overdraftLimit,
            BigDecimal availableBalance, BigDecimal actualBalance) {
        return createAccount.createAccount(customerNumber, type, interestRate, overdraftLimit, availableBalance,
                actualBalance);
    }

    @Override
    public Account updateAccount(long accountNumber, String type, BigDecimal interestRate, int overdraftLimit) {
        return updateAccount.updateAccount(accountNumber, type, interestRate, overdraftLimit);
    }

    @Override
    public Account deleteAccount(long accountNumber) {
        return deleteAccount.deleteAccount(accountNumber);
    }

    @Override
    public Account debitCredit(long accountNumber, BigDecimal amount, boolean payment, String origin14) {
        return debitCredit.debitCredit(accountNumber, amount, payment, origin14);
    }

    @Override
    public Transfer transfer(long fromAccount, long toAccount, BigDecimal amount) {
        TransferResult result = transfer.transfer(fromAccount, toAccount, amount);
        return new Transfer(result.from(), result.to());
    }
}
