package com.ibm.cics.cip.bank.core.proctran;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.repository.ProctranRepository;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the PROCTRAN audit log: port of the Liberty {@code ProcessedTransactionResource.getProcessedTransactionInternal}
 * and {@code ProcessedTransaction.getProcessedTransactions} (this bank's sort code, oldest first, paged).
 */
@Service
public class ProcessedTransactionService {

    static final String TABLE_NOT_ACCESSIBLE =
            "Proctran DB2 table not accessible. Please contact your system administrator.";

    private final ProctranRepository repository;
    private final BankIdentity bank;

    public ProcessedTransactionService(ProctranRepository repository, BankIdentity bank) {
        this.repository = repository;
        this.bank = bank;
    }

    /** @throws CbsaException 500 with the Liberty message when PROCTRAN cannot be read */
    @Transactional(readOnly = true)
    public List<ProcessedTransactionJson> list(int limit, int offset) {
        try {
            return repository.findBySortCode(bank.sortCode(), limit, offset).stream()
                    .map(ProcessedTransactionJson::of)
                    .toList();
        } catch (DataAccessException e) {
            throw CbsaException.serverError(TABLE_NOT_ACCESSIBLE, null);
        }
    }
}
