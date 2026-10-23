package com.ibm.cics.cip.bank.core.screens;

import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.dd;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.mm;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.money;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.rate;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.yyyy;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.zeros;

import java.math.BigDecimal;

import com.ibm.cics.cip.bank.core.common.domain.Account;

/** Account detail fields shared by the BNK1DA and BNK1UA maps (same field names in both mapsets). */
final class AccountFields {

    static final String[] DETAIL = {"SORTC", "CUSTNO", "ACCNO2", "ACTYPE", "OPENDD", "OPENMM", "OPENYY", "OVERDR",
        "LSTMTDD", "LSTMTMM", "LSTMTYY", "NSTMTDD", "NSTMTMM", "NSTMTYY"};

    private AccountFields() {
    }

    static void show(Account a, ScreenOutput.Builder out) {
        out.set("CUSTNO", zeros(a.customerNumber(), 10))
                .set("SORTC", a.sortCode())
                .set("ACCNO2", zeros(a.accountNumber(), 8))
                .set("ACTYPE", a.accountType())
                .set("INTRT", rate(a.interestRate()))
                .set("OPENDD", dd(a.opened())).set("OPENMM", mm(a.opened())).set("OPENYY", yyyy(a.opened()))
                .set("OVERDR", zeros(a.overdraftLimit(), 8))
                .set("LSTMTDD", dd(a.lastStatement())).set("LSTMTMM", mm(a.lastStatement()))
                .set("LSTMTYY", yyyy(a.lastStatement()))
                .set("NSTMTDD", dd(a.nextStatement())).set("NSTMTMM", mm(a.nextStatement()))
                .set("NSTMTYY", yyyy(a.nextStatement()))
                .set("AVBAL", money(a.availableBalance()))
                .set("ACTBAL", money(a.actualBalance()));
    }

    /** MOVE SPACES / ZERO to every detail field (not-found and deleted paths). */
    static void clear(ScreenOutput.Builder out) {
        out.spaces(DETAIL);
        out.set("INTRT", rate(BigDecimal.ZERO))
                .set("AVBAL", money(BigDecimal.ZERO))
                .set("ACTBAL", money(BigDecimal.ZERO));
    }
}
