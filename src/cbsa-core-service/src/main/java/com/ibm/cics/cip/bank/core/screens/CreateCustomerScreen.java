package com.ibm.cics.cip.bank.core.screens;

import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.dd;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.mm;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.pic;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.yyyy;
import static com.ibm.cics.cip.bank.core.screens.ScreenFormat.zeros;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.screens.port.ScreenPorts;

/**
 * BNK1CCS: create customer (map BNK1CC, mapset BNK1CCM, transaction OCCS). PROCESS-MAP -> EDIT-DATA ->
 * CRE-CUST-DATA (LINK CRECUST, which runs the CRDTAGY1-5 credit check).
 */
@Component
public class CreateCustomerScreen extends AbstractScreen {

    public static final String MSG_CLEAR_FIRST = "Please clear screen before creating new user";
    public static final String MSG_TITLE_MISSING = "Valid titles are: Mr,Mrs,Miss,Ms,Dr,Professor,Drs,Lord,Sir,Lady";
    public static final String MSG_TITLE_INVALID = "Valid titles are: Mr,Mrs,Miss,Ms,Dr,Drs,Professor,Lord,Sir,Lady";
    public static final String MSG_FIRST_NAME = "Please supply a valid First Name";
    public static final String MSG_SURNAME = "Please supply a valid Surname";
    public static final String MSG_ADDRESS1 = "Please supply a valid Address Line 1";
    public static final String MSG_DOB_DD = "Please supply a valid Date of Birth DD";
    public static final String MSG_DOB_MM = "Please supply a valid Date of Birth MM";
    public static final String MSG_DOB_YYYY = "Please supply a valid Date of Birth YYYY";
    public static final String MSG_DOB_DD_NUMERIC = "Non numeric Date of Birth DD entered";
    public static final String MSG_DOB_MM_NUMERIC = "Non numeric Date of Birth MM entered";
    public static final String MSG_DOB_YYYY_NUMERIC = "Non numeric Date of Birth YYYY entered";
    public static final String MSG_DOB_DD_RANGE = "Please supply a valid Date of Birth (DD)";
    public static final String MSG_DOB_MM_RANGE = "Please supply a valid Date of Birth (MM)";
    public static final String MSG_CREATED = "The Customer record has been successfully created";
    public static final String MSG_CREATE_FAILED = "Sorry but unable to create Customer record";

    /** EVALUATE CUSTTITI: accepted spellings (after '_' -> ' ' and trailing-space trim) -> stored title. */
    static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("MR", "Mr"), Map.entry("Mr", "Mr"),
            Map.entry("MRS", "Mrs"), Map.entry("Mrs", "Mrs"),
            Map.entry("MISS", "Miss"), Map.entry("Miss", "Miss"),
            Map.entry("MS", "Ms"), Map.entry("Ms", "Ms"), Map.entry("ms", "Ms"),
            Map.entry("DR", "Dr"), Map.entry("Dr", "Dr"), Map.entry("dr", "Dr"),
            Map.entry("DRS", "Drs"), Map.entry("Drs", "Drs"), Map.entry("drs", "Drs"),
            Map.entry("PROFESSOR", "Professor"), Map.entry("Professor", "Professor"),
            Map.entry("LORD", "Lord"), Map.entry("Lord", "Lord"), Map.entry("lord", "Lord"),
            Map.entry("LADY", "Lady"), Map.entry("Lady", "Lady"), Map.entry("lady", "Lady"),
            Map.entry("SIR", "Sir"), Map.entry("Sir", "Sir"), Map.entry("sir", "Sir"));

    /** Lower-case forms the COBOL EVALUATE only accepts with '_' fill ('mr________' but not 'mr        '). */
    static final Map<String, String> UNDERSCORE_ONLY = Map.of("mr", "Mr", "mrs", "Mrs", "miss", "Miss");

    private final ScreenPorts ports;

    public CreateCustomerScreen(ScreenPorts ports) {
        this.ports = ports;
    }

    @Override
    public BmsMap map() {
        return BmsMap.BNK1CC;
    }

    @Override
    protected boolean cursor() {
        return true;
    }

    @Override
    protected String[] firstDisplaySpaces() {
        return new String[] {"CUSTTIT", "CHRISTN", "CUSTINS", "CUSTSN", "CUSTAD1", "CUSTAD2", "CUSTAD3", "MESSAGE"};
    }

    @Override
    protected void processMap(ScreenInput in, ScreenOutput.Builder out) {
        String title = editData(in, out);
        if (title != null) {
            creCustData(in, title, out);
        }
    }

    /** EVALUATE CUSTTITI; null when the title is not accepted. */
    static String normaliseTitle(String raw) {
        String padded = pic(raw, 10);
        String trimmed = ScreenOutput.rtrim(padded.replace('_', ' '));
        String t = TITLES.get(trimmed);
        if (t != null) {
            return t;
        }
        String u = UNDERSCORE_ONLY.get(trimmed);
        return u != null && padded.substring(trimmed.length()).chars().allMatch(c -> c == '_') ? u : null;
    }

    private static boolean blankOrFill(String v, char fill) {
        return v.isEmpty() || v.isBlank() || v.chars().allMatch(c -> c == fill);
    }

    /** EDIT-DATA; returns the normalised title, or null (message set) when invalid. */
    String editData(ScreenInput in, ScreenOutput.Builder out) {
        if (!in.missing("CUSTNO2")) {
            out.message(MSG_CLEAR_FIRST);
            return null;
        }
        if (in.field("CUSTTIT").isBlank()) {
            out.message(MSG_TITLE_MISSING);
            return null;
        }
        String title = normaliseTitle(in.field("CUSTTIT"));
        if (title == null) {
            out.message(MSG_TITLE_INVALID);
            return null;
        }
        out.set("CUSTTIT", title);
        if (blankOrFill(in.field("CHRISTN"), '_')) {
            out.message(MSG_FIRST_NAME);
            return null;
        }
        if (blankOrFill(in.field("CUSTSN"), '_')) {
            out.message(MSG_SURNAME);
            return null;
        }
        String ad1 = in.field("CUSTAD1");
        if (ad1.isBlank() || ad1.charAt(0) == '_') {
            out.message(MSG_ADDRESS1);
            return null;
        }
        String d = in.field("DOBDD");
        String m = in.field("DOBMM");
        String y = in.field("DOBYY");
        if (d.isEmpty() || d.equals("__")) {
            out.message(MSG_DOB_DD);
            return null;
        }
        if (m.isEmpty() || m.equals("__")) {
            out.message(MSG_DOB_MM);
            return null;
        }
        if (y.length() < 4 || y.equals("____")) {
            out.message(MSG_DOB_YYYY);
            return null;
        }
        if (d.length() != 2 || !ScreenFormat.isNumeric(d)) {
            out.message(MSG_DOB_DD_NUMERIC);
            return null;
        }
        if (m.length() != 2 || !ScreenFormat.isNumeric(m)) {
            out.message(MSG_DOB_MM_NUMERIC);
            return null;
        }
        if (!ScreenFormat.isNumeric(y)) {
            out.message(MSG_DOB_YYYY_NUMERIC);
            return null;
        }
        int dd = Integer.parseInt(d);
        if (dd < 1 || dd > 31) {
            out.message(MSG_DOB_DD_RANGE);
            return null;
        }
        int mm = Integer.parseInt(m);
        if (mm < 1 || mm > 12) {
            out.message(MSG_DOB_MM_RANGE);
            return null;
        }
        return title;
    }

    /** CRE-CUST-DATA: build SUBPGM-NAME / SUBPGM-ADDRESS, LINK CRECUST, map the fail code. */
    void creCustData(ScreenInput in, String title, ScreenOutput.Builder out) {
        String name = String.join(" ", firstWord(title), firstWord(in.field("CHRISTN").replace('_', ' ')),
                firstWord(in.field("CUSTINS").replace('_', ' ')), in.field("CUSTSN").replace('_', ' '));
        String address = pic(in.field("CUSTAD1").replace('_', ' '), 60) + pic(in.field("CUSTAD2").replace('_', ' '), 60)
                + pic(in.field("CUSTAD3").replace('_', ' '), 40);
        Customer created;
        try {
            LocalDate dob;
            try {
                dob = LocalDate.of(Integer.parseInt(in.field("DOBYY")), Integer.parseInt(in.field("DOBMM")),
                        Integer.parseInt(in.field("DOBDD")));
            } catch (DateTimeException e) {
                throw CbsaException.badRequest("Invalid date of birth", "Z");
            }
            created = ports.customers().createCustomer(pic(name, 60).stripTrailing(), address, dob);
        } catch (CbsaException e) {
            out.message(switch (failCode(e)) {
                case "O" -> "Sorry, customer is too old. Please check D.O.B.";
                case "Y" -> "Sorry, customer D.O.B. is in the future.";
                case "Z" -> "Sorry, customer D.O.B. is invalid.";
                default -> MSG_CREATE_FAILED;
            });
            showAddress(in, address, out);
            return;
        }
        out.message(MSG_CREATED)
                .set("SORTC", created.sortCode())
                .set("CUSTNO2", zeros(created.customerNumber(), 10))
                .set("DOBDD", dd(created.dateOfBirth()))
                .set("DOBMM", mm(created.dateOfBirth()))
                .set("DOBYY", yyyy(created.dateOfBirth()))
                .set("CREDSC", zeros(created.creditScore(), 3))
                .set("SCRDTDD", dd(created.creditScoreReviewDate()))
                .set("SCRDTMM", mm(created.creditScoreReviewDate()))
                .set("SCRDTYY", yyyy(created.creditScoreReviewDate()));
        showAddress(in, pic(created.address(), 160), out);
    }

    /** WS-ADDR-SPLIT1..3 -> CUSTAD1O..3O; lines never received stay LOW-VALUES (not sent). */
    private static void showAddress(ScreenInput in, String address, ScreenOutput.Builder out) {
        String a = pic(address, 160);
        String[][] parts = {{"CUSTAD1", a.substring(0, 60)}, {"CUSTAD2", a.substring(60, 120)},
            {"CUSTAD3", a.substring(120, 160)}};
        for (String[] p : parts) {
            if (!in.missing(p[0])) {
                out.set(p[0], p[1]);
            }
        }
    }

    /** STRING ... DELIMITED BY SPACE. */
    private static String firstWord(String s) {
        String t = s.stripLeading().isEmpty() ? "" : s;
        int i = t.indexOf(' ');
        return i < 0 ? t : t.substring(0, i);
    }
}
