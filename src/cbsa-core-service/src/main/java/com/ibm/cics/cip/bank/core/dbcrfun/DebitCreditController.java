package com.ibm.cics.cip.bank.core.dbcrfun;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teller debit / credit (DBCRFUN with a non-payment facility type): no funds or account-type check, PROCTRAN
 * {@code DEB 'COUNTER WTHDRW'} / {@code CRE 'COUNTER RECVED'}.
 */
@RestController
@RequestMapping("/banking/account")
public class DebitCreditController {

    /** {@code {"amount": 10.00}}: a positive amount; the endpoint decides the sign. */
    public record AmountRequest(@NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }

    private final DebitCreditService service;

    public DebitCreditController(DebitCreditService service) {
        this.service = service;
    }

    @PutMapping("/debit/{id}")
    public AccountJson debit(@PathVariable("id") long id, @Valid @RequestBody AmountRequest request) {
        return AccountJson.of(service.debitCredit(id, request.amount().negate(), false, null));
    }

    @PutMapping("/credit/{id}")
    public AccountJson credit(@PathVariable("id") long id, @Valid @RequestBody AmountRequest request) {
        return AccountJson.of(service.debitCredit(id, request.amount(), false, null));
    }
}
