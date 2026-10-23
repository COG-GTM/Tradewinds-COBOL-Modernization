package com.ibm.cics.cip.bank.core.delcus;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.domain.ProctranType;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.proctran.ProctranWriter;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import com.ibm.cics.cip.bank.core.common.repository.CustomerRepository;
import com.ibm.cics.cip.bank.core.common.util.CbsaFormats;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of DELCUS: delete a customer and every account they own.
 *
 * <ol>
 *   <li>{@code PREMIERE/A010}: LINK INQCUST - the customer must exist, else fail code {@code 1}.</li>
 *   <li>{@code GET-ACCOUNTS}: LINK INQACCCU - the customer's accounts ordered by account number.</li>
 *   <li>{@code DELETE-ACCOUNTS}: LINK DELACC for each account (each writes PROCTRAN {@code ODA}).</li>
 *   <li>{@code DEL-CUST-VSAM}: READ UPDATE + DELETE of the customer record (abends {@code WPV6} / {@code WPV7});
 *       a record that has vanished in the meantime ({@code NOTFND}) skips the delete and the PROCTRAN row.
 *       Accounts that appeared since {@code GET-ACCOUNTS} (concurrent CREACC) are then swept through DELACC too.</li>
 *   <li>{@code WRITE-PROCTRAN-CUST-DB2}: PROCTRAN {@code ODC} (abend {@code HWPT} if the insert fails).</li>
 * </ol>
 * The whole operation is one transaction (CICS unit of work): any failure rolls back every account delete.
 */
@Service
public class DeleteCustomerService {

    static final String PROGRAM = "DELCUS";

    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final AccountDeletion accountDeletion;
    private final ProctranWriter proctran;
    private final BankIdentity bank;

    public DeleteCustomerService(CustomerRepository customers, AccountRepository accounts,
            AccountDeletion accountDeletion, ProctranWriter proctran, BankIdentity bank) {
        this.customers = customers;
        this.accounts = accounts;
        this.accountDeletion = accountDeletion;
        this.proctran = proctran;
        this.bank = bank;
    }

    /**
     * Deletes the customer, all of their accounts, and writes the PROCTRAN audit rows.
     *
     * @return the customer record as deleted
     * @throws CbsaException 404 fail code {@code 1} if the customer does not exist; the DELACC failure if an
     *         account cannot be deleted
     * @throws CbsaAbendException {@code WPV6}/{@code WPV7} customer read/delete failure, {@code HWPT} PROCTRAN
     *         write failure
     */
    @Transactional
    public Customer deleteCustomer(long customerNumber) {
        String sortCode = bank.sortCode();

        // A010: LINK INQCUST
        Customer inquired = customers.findByKey(sortCode, customerNumber)
                .orElseThrow(() -> CbsaException.notFound("Customer number " + customerNumber + " not found.", "1"));

        // GET-ACCOUNTS / DELETE-ACCOUNTS
        deleteAccounts(sortCode, customerNumber);

        // DEL-CUST-VSAM
        Optional<Customer> current = readForDelete(sortCode, customerNumber);
        if (current.isEmpty()) {
            return inquired;
        }
        Customer deleted = current.get();
        if (!deleteRecord(sortCode, customerNumber)) {
            return inquired;
        }
        // Accounts committed by a concurrent CREACC after GET-ACCOUNTS ran would otherwise be orphaned.
        deleteAccounts(sortCode, customerNumber);

        writeProctran(deleted);
        return deleted;
    }

    /** GET-ACCOUNTS + DELETE-ACCOUNTS: every account of the customer, in account-number order. */
    private void deleteAccounts(String sortCode, long customerNumber) {
        List<Account> owned = accounts.findByCustomer(sortCode, customerNumber);
        for (Account account : owned) {
            deleteAccount(customerNumber, account.accountNumber());
        }
    }

    /** DA010: LINK DELACC for one account. */
    private void deleteAccount(long customerNumber, long accountNumber) {
        try {
            accountDeletion.deleteAccount(accountNumber);
        } catch (CbsaAbendException e) {
            throw e;
        } catch (CbsaException e) {
            String msg = "Error deleting account " + accountNumber + " for customer " + customerNumber;
            if (e.status() == HttpStatus.NOT_FOUND) {
                throw new CbsaException(HttpStatus.NOT_FOUND, msg + ",account not found", e.failCode());
            }
            throw new CbsaException(e.status(), msg, e.failCode());
        }
    }

    /** DCV010: EXEC CICS READ FILE('CUSTOMER') UPDATE; NOTFND -> GO TO DCV999. */
    private Optional<Customer> readForDelete(String sortCode, long customerNumber) {
        try {
            return customers.findByKey(sortCode, customerNumber);
        } catch (DataAccessException e) {
            throw new CbsaAbendException("WPV6", PROGRAM, "DCV010 - Unable to READ CUSTOMER VSAM rec for key:"
                    + sortCode + CbsaFormats.customerNumber(customerNumber));
        }
    }

    /** DCV010: EXEC CICS DELETE FILE('CUSTOMER') TOKEN(..); returns false if the row had already gone. */
    private boolean deleteRecord(String sortCode, long customerNumber) {
        try {
            return customers.delete(sortCode, customerNumber) > 0;
        } catch (DataAccessException e) {
            throw new CbsaAbendException("WPV7", PROGRAM, "DCV010(2) - Unable to DELETE CUSTOMER VSAM rec for key:"
                    + sortCode + CbsaFormats.customerNumber(customerNumber));
        }
    }

    /** WPCD010: PROCTRAN ODC, desc sortcode(6) + custno(10) + name(14) + dob dd/mm/yyyy, amount 0. */
    private void writeProctran(Customer deleted) {
        try {
            proctran.deleteCustomer(ProctranType.BRANCH_DELETE_CUSTOMER, deleted);
        } catch (DataAccessException e) {
            throw new CbsaAbendException("HWPT", PROGRAM,
                    "WPCD010 - Unable to WRITE to PROCTRAN DB2 datastore for customer "
                            + CbsaFormats.customerNumber(deleted.customerNumber()));
        }
    }
}
