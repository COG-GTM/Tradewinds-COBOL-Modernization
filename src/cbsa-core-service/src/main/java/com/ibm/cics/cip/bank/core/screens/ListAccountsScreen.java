package com.ibm.cics.cip.bank.core.screens;

import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.money;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.pic;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.zeros;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.screens.port.ScreenPorts;

/**
 * BNK1CCA: list the accounts of a customer (map BNK1ACC, mapset BNK1ACC, transaction OCCA). PROCESS-MAP ->
 * EDIT-DATA -> GET-CUST-DATA (LINK INQACCCU); at most 10 ACCOUNTO lines are shown.
 */
@Component
public class ListAccountsScreen extends AbstractScreen {

    public static final String MSG_ENTER_CUSTNO = "Please enter a customer number.";
    public static final String MSG_NO_ACCOUNTS = "No accounts found for customer";
    static final int LINES = 10;

    private final ScreenPorts ports;

    public ListAccountsScreen(ScreenPorts ports) {
        this.ports = ports;
    }

    @Override
    public BmsMap map() {
        return BmsMap.BNK1ACC;
    }

    @Override
    protected boolean cursor() {
        return false;
    }

    @Override
    protected void processMap(ScreenInput in, ScreenOutput.Builder out) {
        String custno = in.field("CUSTNO");
        // EDIT-DATA: CUSTNOI (PIC X(10)) NOT NUMERIC
        if (custno.length() != 10 || !ScreenFormat.isNumeric(custno)) {
            out.message(MSG_ENTER_CUSTNO);
            return;
        }
        getCustData(custno, out);
    }

    /** GET-CUST-DATA. */
    void getCustData(String custno, ScreenOutput.Builder out) {
        List<Account> accounts;
        for (int i = 1; i <= LINES; i++) {
            out.spaces(line(i));
        }
        try {
            accounts = ports.accounts().accountsForCustomer(Long.parseLong(custno));
        } catch (CbsaAbendException e) {
            throw e;
        } catch (CbsaException e) {
            if ("1".equals(failCode(e))) {
                out.message("Unable to find customer " + custno);
            } else {
                out.message("Error accessing accounts for customer " + custno + ".");
            }
            return;
        }
        if (accounts.isEmpty()) {
            out.message(MSG_NO_ACCOUNTS);
            return;
        }
        out.message(String.format("%2d", accounts.size()) + " accounts found");
        for (int i = 0; i < accounts.size() && i < LINES; i++) {
            out.set(line(i + 1), accountLine(accounts.get(i)));
        }
    }

    /** STRING SCODE-CHAR '      ' ACCNO-CHAR '         ' ACC-TYPE '       ' avail '  ' actual. */
    static String accountLine(Account a) {
        return pic(a.sortCode(), 6) + "      " + zeros(a.accountNumber(), 8) + "         "
                + pic(a.accountType(), 8) + "       " + money(a.availableBalance()) + "  "
                + money(a.actualBalance());
    }

    static String line(int i) {
        return "ACCOUNT(" + i + ")";
    }
}
