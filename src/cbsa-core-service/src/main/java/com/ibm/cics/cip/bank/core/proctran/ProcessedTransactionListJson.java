package com.ibm.cics.cip.bank.core.proctran;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Body of {@code GET /banking/processedTransaction}. {@code numberOfProcessedTransactions} is the contract
 * name; {@code numberOfProcessedTransactionRecords} and {@code success} keep the Liberty
 * {@code ProcessedTransactionResource} keys for existing clients.
 */
@JsonPropertyOrder({"numberOfProcessedTransactions", "numberOfProcessedTransactionRecords", "processedTransactions",
        "success"})
public record ProcessedTransactionListJson(
        int numberOfProcessedTransactions,
        int numberOfProcessedTransactionRecords,
        List<ProcessedTransactionJson> processedTransactions,
        String success) {

    public static ProcessedTransactionListJson of(List<ProcessedTransactionJson> rows) {
        return new ProcessedTransactionListJson(rows.size(), rows.size(), rows, "Y");
    }
}
