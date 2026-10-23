package com.ibm.cics.cip.bank.core.proctran;

import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /banking/processedTransaction?limit=&offset=} (docs/migration/api-contract.md, "Optional read APIs"). */
@RestController
@RequestMapping("/banking/processedTransaction")
public class ProcessedTransactionController {

    /** Liberty ProcessedTransactionResource default when limit is absent. */
    static final int DEFAULT_LIMIT = 250_000;

    private final ProcessedTransactionService service;

    public ProcessedTransactionController(ProcessedTransactionService service) {
        this.service = service;
    }

    @GetMapping
    public ProcessedTransactionListJson list(@RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset) {
        int realLimit = limit == null ? DEFAULT_LIMIT : limit;
        int realOffset = offset == null ? 0 : offset;
        if (realLimit < 0 || realOffset < 0) {
            throw CbsaException.badRequest("limit and offset must not be negative");
        }
        return ProcessedTransactionListJson.of(service.list(realLimit, realOffset));
    }
}
