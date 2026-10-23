package com.ibm.cics.cip.bank.core.bankdata;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.domain.ControlRecord;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import java.util.List;

/**
 * Output of one BANKDATA run: the CUSTOMER rows (legacy VSAM customer file), the ACCOUNT rows, and the
 * CONTROL rows ({@code <sortcode>-ACCOUNT-COUNT}, {@code -ACCOUNT-LAST}, {@code -CUSTOMER-COUNT},
 * {@code -CUSTOMER-LAST}, in CONTROL_NAME order). Rows are in key order.
 */
public record GeneratedBank(String sortCode, List<Customer> customers, List<Account> accounts,
        List<ControlRecord> controls) {

    public GeneratedBank {
        customers = List.copyOf(customers);
        accounts = List.copyOf(accounts);
        controls = List.copyOf(controls);
    }
}
