package com.ibm.cics.cip.bank.core.bankdata;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.domain.ControlRecord;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import com.ibm.cics.cip.bank.core.common.repository.CustomerRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a {@link GeneratedBank} to the database as BANKDATA does: {@code DELETE-DB2-ROWS} (ACCOUNT rows
 * and the ACCOUNT-LAST / ACCOUNT-COUNT CONTROL rows of the sort code), {@code OPEN OUTPUT CUSTOMER-FILE}
 * (the customer file is recreated, so the sort code's CUSTOMER rows and CUSTOMER-COUNT / CUSTOMER-LAST are
 * replaced), then the generated rows. PROCTRAN is not touched (BANKDATA never reads or writes it).
 */
@Service
public class BankDataLoader {

    private final JdbcClient jdbc;
    private final CustomerRepository customers;
    private final AccountRepository accounts;

    public BankDataLoader(JdbcClient jdbc, CustomerRepository customers, AccountRepository accounts) {
        this.jdbc = jdbc;
        this.customers = customers;
        this.accounts = accounts;
    }

    @Transactional
    public void load(GeneratedBank bank) {
        String sortCode = bank.sortCode();
        jdbc.sql("DELETE FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ?").param(sortCode).update();
        jdbc.sql("DELETE FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ?").param(sortCode).update();
        for (String suffix : new String[] {"-ACCOUNT-LAST", "-ACCOUNT-COUNT", "-CUSTOMER-LAST", "-CUSTOMER-COUNT"}) {
            jdbc.sql("DELETE FROM CONTROL WHERE CONTROL_NAME = ?").param(sortCode + suffix).update();
        }
        for (Customer c : bank.customers()) {
            customers.insert(c);
        }
        for (Account a : bank.accounts()) {
            accounts.insert(a);
        }
        for (ControlRecord r : bank.controls()) {
            jdbc.sql("INSERT INTO CONTROL (CONTROL_NAME, CONTROL_VALUE_NUM, CONTROL_VALUE_STR) VALUES (?, ?, ?)")
                    .params(r.name(), r.valueNum(), r.valueStr()).update();
        }
    }

}
