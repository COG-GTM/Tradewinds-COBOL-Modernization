package com.ibm.cics.cip.bank.core.screens;

import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.dd;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.deedit;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.mm;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.pic;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.yyyy;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.zeros;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.screens.port.ScreenPorts;

/**
 * BNK1DCS: display / update / delete customer (map BNK1DC, mapset BNK1DCM, transaction ODCS).
 * ENTER looks up (GET-CUST-DATA -> INQCUST); PF5 deletes (DEL-CUST-DATA -> DELCUS); PF10 unprotects the
 * fields (UNPROT-CUST-DATA) and the next ENTER updates (EDIT-DATA2, UPDATE-CUST-DATA -> UPDCUST).
 */
@Component
public class DisplayCustomerScreen extends AbstractScreen {

    public static final String MSG_ENTER_CUSTNO = "Please enter a customer number.";
    public static final String MSG_NOT_FOUND = "Sorry, but that customer number was not found.";
    public static final String MSG_LOOKUP_OK = "Customer lookup successful.";
    public static final String MSG_LOOKUP_OK_ACTIONS =
            "Customer lookup successful. <PF5> to Delete. <PF10> to Update.";
    public static final String MSG_BAD_SORTCODE = "The Sort code / Customer number combination is not VALID.";
    public static final String MSG_BAD_CUSTNO = "The customer number is not VALID.";
    public static final String MSG_AMEND = "Amend data then press <ENTER>.";
    public static final String MSG_BAD_TITLE = "Valid titles are: Mr,Mrs,Miss,Ms,Dr,Professor,Drs,Lord,Sir,Lady";
    public static final String MSG_BLANK_ADDRESS = "Address must not be all spaces - please reenter";

    static final Set<String> TITLES =
            Set.of("Professor", "Mr", "Mrs", "Miss", "Ms", "Dr", "Drs", "Lord", "Sir", "Lady");

    private static final String[] DETAIL_FIELDS = {"SORTC", "CUSTNO2", "CUSTNAM", "CUSTAD1", "CUSTAD2",
        "CUSTAD3", "DOBDD", "DOBMM", "DOBYY", "CREDSC", "SCRDTDD", "SCRDTMM", "SCRDTYY"};

    private final ScreenPorts ports;

    public DisplayCustomerScreen(ScreenPorts ports) {
        this.ports = ports;
    }

    @Override
    public BmsMap map() {
        return BmsMap.BNK1DC;
    }

    @Override
    protected boolean cursor() {
        return true;
    }

    @Override
    protected Set<AidKey> processKeys() {
        return Set.of(AidKey.ENTER, AidKey.PF5, AidKey.PF10);
    }

    /** PROCESS-MAP. */
    @Override
    protected void processMap(ScreenInput in, ScreenOutput.Builder out) {
        boolean updating = "Y".equals(in.state("UPD"));
        if (in.aid() == AidKey.ENTER && !updating) {
            Long custno = editData(in, out);
            if (custno != null) {
                getCustData(custno, out);
            }
        } else if (in.aid() == AidKey.ENTER) {
            if (editData2(in, out)) {
                updateCustData(in, out);
            }
        } else if (in.aid() == AidKey.PF5) {
            Long custno = editData(in, out);
            if (validateData(in, custno, out) && custno != null) {
                delCustData(in, custno, out);
            }
        } else if (in.aid() == AidKey.PF10) {
            Long custno = editData(in, out);
            if (validateData(in, custno, out) && custno != null) {
                unprotCustData(out);
                out.message(MSG_AMEND);
            }
        }
    }

    /** EDIT-DATA: CUSTNO must be present; BIF DEEDIT makes it numeric. Returns null when invalid. */
    Long editData(ScreenInput in, ScreenOutput.Builder out) {
        if (in.missing("CUSTNO")) {
            out.message(MSG_ENTER_CUSTNO);
            return null;
        }
        String custno = deedit(in.field("CUSTNO"), 10);
        out.set("CUSTNO", custno);
        return Long.parseLong(custno);
    }

    /** VALIDATE-DATA. */
    boolean validateData(ScreenInput in, Long custno, ScreenOutput.Builder out) {
        boolean valid = custno != null;
        if ("000000".equals(in.field("SORTC"))) {
            out.message(MSG_BAD_SORTCODE);
            valid = false;
        }
        if (custno != null && (custno == 0L || custno == 9_999_999_999L)) {
            out.message(MSG_BAD_CUSTNO);
            valid = false;
        }
        return valid;
    }

    /** GET-CUST-DATA: LINK INQCUST. */
    void getCustData(long custno, ScreenOutput.Builder out) {
        Optional<Customer> found;
        try {
            found = ports.customers().inquireCustomer(custno);
        } catch (CbsaException e) {
            found = Optional.empty();
        }
        if (found.isEmpty()) {
            out.message(MSG_NOT_FOUND).spaces(DETAIL_FIELDS);
            return;
        }
        showCustomer(found.get(), out);
        out.message(custno == 0L || custno == 9_999_999_999L ? MSG_LOOKUP_OK : MSG_LOOKUP_OK_ACTIONS);
    }

    private static void showCustomer(Customer c, ScreenOutput.Builder out) {
        String addr = pic(c.address(), 160);
        out.set("SORTC", c.sortCode())
                .set("CUSTNO2", zeros(c.customerNumber(), 10))
                .set("CUSTNAM", c.name())
                .set("CUSTAD1", addr.substring(0, 60))
                .set("CUSTAD2", addr.substring(60, 120))
                .set("CUSTAD3", addr.substring(120, 160))
                .set("DOBDD", dd(c.dateOfBirth()))
                .set("DOBMM", mm(c.dateOfBirth()))
                .set("DOBYY", yyyy(c.dateOfBirth()))
                .set("CREDSC", zeros(c.creditScore(), 3))
                .set("SCRDTDD", dd(c.creditScoreReviewDate()))
                .set("SCRDTMM", mm(c.creditScoreReviewDate()))
                .set("SCRDTYY", yyyy(c.creditScoreReviewDate()));
    }

    /** DEL-CUST-DATA: LINK DELCUS with the displayed customer (CUSTNO2I). */
    void delCustData(ScreenInput in, long custno, ScreenOutput.Builder out) {
        long target = in.missing("CUSTNO2") ? custno : Long.parseLong(deedit(in.field("CUSTNO2"), 10));
        Customer deleted;
        try {
            deleted = ports.customers().deleteCustomer(target);
        } catch (CbsaException e) {
            out.message(switch (failCode(e)) {
                case "1" -> "Sorry but that Cust no was not found. Customer NOT deleted.";
                case "2" -> "Sorry but a datastore error occurred. Action NOT applied.";
                case "3" -> "Sorry but a delete error occurred. Customer NOT deleted.";
                default -> "Sorry but an error occurred. Customer NOT deleted.";
            });
            return;
        }
        out.spaces(DETAIL_FIELDS);
        out.message("Customer " + zeros(deleted.customerNumber(), 10)
                + " and associated accounts were successfully deleted.");
    }

    /** UNPROT-CUST-DATA: remember the displayed customer in the COMMAREA and set COMM-UPD = 'Y'. */
    void unprotCustData(ScreenOutput.Builder out) {
        out.state("UPD", "Y");
        out.state("CUSTNO", nz(out.get("CUSTNO2")));
        out.state("SCODE", nz(out.get("SORTC")));
    }

    /** EDIT-DATA2: title (first word of CUSTNAM) and address validation. */
    boolean editData2(ScreenInput in, ScreenOutput.Builder out) {
        boolean valid = true;
        String name = in.field("CUSTNAM").stripLeading();
        String title = name.isEmpty() ? "" : name.split(" ", 2)[0];
        if (!TITLES.contains(title)) {
            out.message(MSG_BAD_TITLE);
            valid = false;
        }
        if (in.field("CUSTAD1").isBlank() && in.field("CUSTAD2").isBlank() && in.field("CUSTAD3").isBlank()) {
            out.message(MSG_BLANK_ADDRESS);
            valid = false;
        }
        return valid;
    }

    /** UPDATE-CUST-DATA: LINK UPDCUST, then PROT-CUST-DATA. */
    void updateCustData(ScreenInput in, ScreenOutput.Builder out) {
        String custnoText = in.missing("CUSTNO2") ? in.state("CUSTNO") : in.field("CUSTNO2");
        long custno = Long.parseLong(deedit(custnoText, 10));
        String address = pic(in.field("CUSTAD1"), 60) + pic(in.field("CUSTAD2"), 60) + pic(in.field("CUSTAD3"), 40);
        Customer updated;
        try {
            updated = ports.customers().updateCustomer(custno, in.field("CUSTNAM"), address);
        } catch (CbsaException e) {
            out.message(switch (failCode(e)) {
                case "1" -> "Sorry but that Cust no was not found. Customer NOT updated.";
                case "2" -> "Sorry but a datastore error occurred. Customer NOT updated.";
                case "3" -> "Sorry but an update error occurred. Customer NOT updated.";
                default -> "Sorry but an unknown error occurred. Customer NOT updated.";
            });
            return;
        }
        showCustomer(updated, out);
        out.set("CUSTNO", zeros(updated.customerNumber(), 10));
        out.message("Customer " + zeros(updated.customerNumber(), 10) + " was updated successfully");
        out.state("UPD", "N");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
