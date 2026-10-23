package com.ibm.cics.cip.bank.core.proctran;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ibm.cics.cip.bank.core.common.domain.ProcessedTransaction;
import com.ibm.cics.cip.bank.core.common.domain.ProctranType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Set;

/**
 * One PROCTRAN row as returned by {@code GET /banking/processedTransaction}. The contract fields come first;
 * the type-specific fields are decoded from the positional PROC-TRAN-DESC layouts (PROCTRAN.cpy) exactly as
 * the Liberty {@code ProcessedTransaction} / {@code ProcessedTransactionResource} classes do, and are omitted
 * for other types or when the description does not hold that layout.
 *
 * <p>{@code date} / {@code time} are the contract fields (ISO {@code yyyy-MM-dd} / {@code HH:mm:ss}). The fields kept
 * from Liberty ({@code timestamp}, {@code dateOfBirth}, {@code lastStatement}, {@code nextStatement}) keep Liberty's
 * rendering: {@code DateFormat.getDateTimeInstance()} / {@code getDateInstance()} (MEDIUM style), pinned to
 * {@link Locale#US} and the stored wall-clock time so the output does not depend on the JVM locale or time zone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"sortCode", "accountNumber", "date", "time", "timestamp", "reference", "type", "description",
        "amount", "customer", "customerName", "dateOfBirth", "accountType", "lastStatement", "nextStatement",
        "targetSortcode", "targetAccount"})
public record ProcessedTransactionJson(
        String sortCode,
        String accountNumber,
        String date,
        String time,
        String timestamp,
        String reference,
        String type,
        String description,
        BigDecimal amount,
        String customer,
        String customerName,
        String dateOfBirth,
        String accountType,
        String lastStatement,
        String nextStatement,
        String targetSortcode,
        String targetAccount) {

    private static final Set<String> CUSTOMER_TYPES = Set.of(ProctranType.WEB_CREATE_CUSTOMER.code(),
            ProctranType.WEB_DELETE_CUSTOMER.code(), ProctranType.BRANCH_CREATE_CUSTOMER.code(),
            ProctranType.BRANCH_DELETE_CUSTOMER.code());
    private static final Set<String> ACCOUNT_TYPES = Set.of(ProctranType.WEB_CREATE_ACCOUNT.code(),
            ProctranType.WEB_DELETE_ACCOUNT.code(), ProctranType.BRANCH_CREATE_ACCOUNT.code(),
            ProctranType.BRANCH_DELETE_ACCOUNT.code());
    private static final DateTimeFormatter DD_MM_YYYY = DateTimeFormatter.ofPattern("ddMMuuuu");
    static final DateTimeFormatter LIBERTY_DATE =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.US);
    static final DateTimeFormatter LIBERTY_DATE_TIME =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.US);
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    public static ProcessedTransactionJson of(ProcessedTransaction p) {
        String type = trim(p.type());
        String desc = p.description() == null ? "" : p.description();
        String date = p.date() == null ? null : p.date().toString();
        String time = time(p.time());
        String timestamp = p.date() == null ? null : LIBERTY_DATE_TIME.format(p.date().atTime(clockTime(p.time())));

        String customer = null;
        String customerName = null;
        String dateOfBirth = null;
        String accountType = null;
        String lastStatement = null;
        String nextStatement = null;
        String targetSortcode = null;
        String targetAccount = null;

        if (ProctranType.TRANSFER.code().equals(type) && desc.length() >= 40) {
            // XFRFUN: 'TRANSFER'(26) + to-sortcode(6) + to-account(8)
            targetSortcode = desc.substring(26, 32);
            targetAccount = desc.substring(32, 40);
        } else if (ACCOUNT_TYPES.contains(type) && desc.length() >= 34) {
            // CREACC / DELACC: custno(10) + type(8) + last-stmt ddmmyyyy(8) + next-stmt ddmmyyyy(8)
            LocalDate last = parse(desc.substring(18, 26));
            LocalDate next = parse(desc.substring(26, 34));
            if (last != null && next != null) {
                customer = desc.substring(0, 10);
                accountType = desc.substring(10, 18).trim();
                lastStatement = LIBERTY_DATE.format(last);
                nextStatement = LIBERTY_DATE.format(next);
            }
        } else if (CUSTOMER_TYPES.contains(type) && desc.length() >= 40) {
            // CRECUST / DELCUS: sortcode(6) + custno(10) + name(14) + dob dd/mm/yyyy
            String dob = desc.substring(30, 40);
            LocalDate born = parse(dob.substring(0, 2) + dob.substring(3, 5) + dob.substring(6, 10));
            if (born != null) {
                customer = desc.substring(6, 16);
                customerName = desc.substring(16, 30).trim();
                dateOfBirth = LIBERTY_DATE.format(born);
            }
        }

        return new ProcessedTransactionJson(trim(p.sortCode()), trim(p.number()), date, time, timestamp,
                trim(p.reference()), type, desc.trim(),
                p.amount() == null ? null : p.amount().setScale(2, RoundingMode.HALF_UP), customer, customerName,
                dateOfBirth, accountType, lastStatement, nextStatement, targetSortcode, targetAccount);
    }

    /** PROCTRAN_TIME HHMMSS as HH:mm:ss; any other content is returned trimmed. */
    private static String time(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return t.length() == 6 && t.chars().allMatch(Character::isDigit)
                ? t.substring(0, 2) + ":" + t.substring(2, 4) + ":" + t.substring(4, 6)
                : t;
    }

    /** Liberty adds HH/MM/SS only when PROCTRAN_TIME has 6 characters; otherwise midnight. */
    private static LocalTime clockTime(String raw) {
        String t = raw == null ? "" : raw.trim();
        try {
            return t.length() == 6 ? LocalTime.parse(t, HHMMSS) : LocalTime.MIDNIGHT;
        } catch (DateTimeParseException e) {
            return LocalTime.MIDNIGHT;
        }
    }

    private static LocalDate parse(String ddmmyyyy) {
        try {
            return LocalDate.parse(ddmmyyyy, DD_MM_YYYY);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
