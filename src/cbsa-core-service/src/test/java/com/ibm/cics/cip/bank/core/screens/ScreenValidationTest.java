package com.ibm.cics.cip.bank.core.screens;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.ibm.cics.cip.bank.core.common.domain.Account;

/**
 * Table-driven EDIT-DATA / VALIDATE-DATA tests per BNK1* screen. Every case fails validation before any
 * business service is LINKed, so the handlers run without service ports. Messages are the COBOL literals.
 */
class ScreenValidationTest {

    static Map<String, String> f(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    static String message(ScreenHandler h, AidKey aid, Map<String, String> fields) {
        return ScreenOutput.rtrim(h.handle(new ScreenInput(aid, fields, Map.of())).fields().getOrDefault("MESSAGE", ""));
    }

    static Stream<Arguments> bnk1cac() {
        Map<String, String> ok = f("CUSTNO", "0000000019", "ACCTYP", "SAVING  ", "INTRT", "1.25", "OVERDR", "0");
        return Stream.of(
                Arguments.of(f(), "Please enter a 10 digit Customer Number"),
                Arguments.of(with(ok, "ACCTYP", "CHEQUE  "), "Account Type should be ISA,CURRENT,LOAN,SAVING or MORTGAGE"),
                Arguments.of(with(ok, "ACCTYP", "Saving"), "Account Type should be ISA,CURRENT,LOAN,SAVING or MORTGAGE"),
                Arguments.of(with(ok, "ACCTYP", ""), "Account Type should be ISA,CURRENT,LOAN,SAVING or MORTGAGE"),
                Arguments.of(with(ok, "INTRT", ""), "Please supply a numeric interest rate"),
                Arguments.of(with(ok, "INTRT", "abc"), "Please supply a numeric interest rate"),
                Arguments.of(with(ok, "INTRT", "1.2.3"), "Use one decimal point for interest rate only"),
                Arguments.of(with(ok, "INTRT", "1.234"), "Only up to two decimal places are supported"),
                Arguments.of(with(ok, "INTRT", "-1"), "Please supply a zero or positive interest rate"),
                Arguments.of(with(ok, "INTRT", "10000"), "Please supply an interest rate less than 9999.99%"),
                Arguments.of(with(ok, "OVERDR", "12a"), "Overdraft Limit must be numeric positive int"),
                Arguments.of(with(ok, "OVERDR", "-5"), "Overdraft Limit must be numeric positive int"));
    }

    static Map<String, String> with(Map<String, String> base, String k, String v) {
        Map<String, String> m = new HashMap<>(base);
        m.put(k, v);
        return m;
    }

    @ParameterizedTest
    @MethodSource
    void bnk1cac(Map<String, String> fields, String expected) {
        assertThat(message(new CreateAccountScreen(null), AidKey.ENTER, fields)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1|The supplied customer number does not exist.",
        "2|The customer data cannot be accessed,  unable to create account.",
        "3|Account record creation failed. (unable to ENQ ACCOUNT NC).",
        "4|Account record creation failed, (unable to increment ACCOUNT NC).",
        "5|Account record creation failed, (unable to restore ACCOUNT NC).",
        "6|Account record creation failed, (unable to WRITE to ACCOUNT file).",
        "7|Account record creation failed, (unable to INSERT into ACCOUNT).",
        "8|Account record creation failed, (too many accounts).",
        "9|Account record creation failed,  unable to count accounts.",
        "A|Account record creation failed,  account type unsupported.",
        "Z|The account was not created."})
    void bnk1cacFailCodes(String failCode, String expected) {
        assertThat(CreateAccountScreen.failMessage(failCode)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"isa_____,ISA", "ISA,ISA", "current,CURRENT", "MORTGAGE,MORTGAGE", "loan    ,LOAN", "Loan,"})
    void bnk1cacAccountTypes(String raw, String expected) {
        assertThat(CreateAccountScreen.normaliseType(raw)).isEqualTo(expected);
    }

    static Stream<Arguments> bnk1ccs() {
        Map<String, String> ok = f("CUSTTIT", "Mr", "CHRISTN", "Screen", "CUSTSN", "Tester", "CUSTAD1", "1 Street",
                "DOBDD", "01", "DOBMM", "02", "DOBYY", "1980");
        String valid = "Valid titles are: Mr,Mrs,Miss,Ms,Dr,Drs,Professor,Lord,Sir,Lady";
        return Stream.of(
                Arguments.of(with(ok, "CUSTNO2", "0000000001"), "Please clear screen before creating new user"),
                Arguments.of(f(), "Valid titles are: Mr,Mrs,Miss,Ms,Dr,Professor,Drs,Lord,Sir,Lady"),
                Arguments.of(with(ok, "CUSTTIT", "King"), valid),
                Arguments.of(with(ok, "CUSTTIT", "mr"), valid),
                Arguments.of(with(ok, "CUSTTIT", "professor"), valid),
                Arguments.of(with(ok, "CHRISTN", "____________________"), "Please supply a valid First Name"),
                Arguments.of(with(ok, "CHRISTN", ""), "Please supply a valid First Name"),
                Arguments.of(with(ok, "CUSTSN", "  "), "Please supply a valid Surname"),
                Arguments.of(with(ok, "CUSTAD1", "_x"), "Please supply a valid Address Line 1"),
                Arguments.of(with(ok, "DOBDD", ""), "Please supply a valid Date of Birth DD"),
                Arguments.of(with(ok, "DOBMM", "__"), "Please supply a valid Date of Birth MM"),
                Arguments.of(with(ok, "DOBYY", "19"), "Please supply a valid Date of Birth YYYY"),
                Arguments.of(with(ok, "DOBDD", "ab"), "Non numeric Date of Birth DD entered"),
                Arguments.of(with(ok, "DOBMM", "1a"), "Non numeric Date of Birth MM entered"),
                Arguments.of(with(ok, "DOBYY", "19a0"), "Non numeric Date of Birth YYYY entered"),
                Arguments.of(with(ok, "DOBDD", "32"), "Please supply a valid Date of Birth (DD)"),
                Arguments.of(with(ok, "DOBMM", "13"), "Please supply a valid Date of Birth (MM)"));
    }

    @ParameterizedTest
    @MethodSource
    void bnk1ccs(Map<String, String> fields, String expected) {
        assertThat(message(new CreateCustomerScreen(null), AidKey.ENTER, fields)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"mr________,Mr", "MRS,Mrs", "miss______,Miss", "ms,Ms", "drs,Drs", "PROFESSOR,Professor",
        "lady,Lady", "Sir,Sir"})
    void bnk1ccsTitles(String raw, String expected) {
        assertThat(CreateCustomerScreen.normaliseTitle(raw)).isEqualTo(expected);
    }

    static Stream<Arguments> bnk1cra() {
        Map<String, String> ok = f("ACCNO", "00000001", "SIGN", "+", "AMT", "20.00");
        return Stream.of(
                Arguments.of(f(), "Please enter an account number."),
                Arguments.of(with(ok, "ACCNO", "00000000"), "Please enter a non zero account number."),
                Arguments.of(with(ok, "SIGN", "*"), "Please enter + or - preceding the amount"),
                Arguments.of(with(ok, "AMT", ""), "The Amount entered must be numeric."),
                Arguments.of(with(ok, "AMT", "   "), "The Amount entered must be numeric."),
                Arguments.of(with(ok, "AMT", "1 0"), "Please supply a numeric amount without embedded"),
                Arguments.of(with(ok, "AMT", "1a"), "Please supply a numeric amount."),
                Arguments.of(with(ok, "AMT", "1.2.3"), "Use one decimal point for amount only."),
                Arguments.of(with(ok, "AMT", "1.234"), "Only up to two decimal places are supported."),
                Arguments.of(with(ok, "AMT", "0"), "Please supply a non-zero amount."),
                Arguments.of(with(ok, "AMT", "0.00"), "Please supply a non-zero amount."));
    }

    @ParameterizedTest
    @MethodSource
    void bnk1cra(Map<String, String> fields, String expected) {
        assertThat(message(new CreditDebitScreen(null, null), AidKey.ENTER, fields)).isEqualTo(expected);
    }

    static Stream<Arguments> bnk1tfn() {
        Map<String, String> ok = f("FACCNO", "00000001", "TACCNO", "00000002", "AMT", "10.00");
        return Stream.of(
                Arguments.of(f(), "Please enter a FROM account no"),
                Arguments.of(f("FACCNO", "00000001"), "Please enter a TO account no"),
                Arguments.of(with(ok, "TACCNO", "00000001"), "The FROM & TO account should be different"),
                Arguments.of(with(ok, "FACCNO", "00000000"), "Account no 00000000 is not valid"),
                Arguments.of(with(ok, "AMT", "ten"), "Please supply a numeric amount."),
                Arguments.of(with(ok, "AMT", ""), "The Amount entered must be numeric."),
                Arguments.of(with(ok, "AMT", "-5"), "Please supply a positive amount."),
                Arguments.of(with(ok, "AMT", "0"), "Please supply a positive amount."),
                Arguments.of(with(ok, "AMT", "1 0"), "Please supply a numeric amount without embedded  spaces."),
                Arguments.of(with(ok, "AMT", "1.2.3"), "Use one decimal point for amount only."),
                Arguments.of(with(ok, "AMT", "1.234"), "Only up to two decimal places are supported."));
    }

    @ParameterizedTest
    @MethodSource
    void bnk1tfn(Map<String, String> fields, String expected) {
        assertThat(message(new TransferScreen(null), AidKey.ENTER, fields)).isEqualTo(expected);
    }

    static Stream<Arguments> bnk1uac() {
        Map<String, String> ok = f("ACCNO", "00000003", "ACTYPE", "CURRENT", "INTRT", "1.50", "OVERDR", "00000100",
                "LSTMTDD", "01", "LSTMTMM", "07", "LSTMTYY", "2021", "NSTMTDD", "01", "NSTMTMM", "08", "NSTMTYY", "2021");
        return Stream.of(
                Arguments.of(with(ok, "ACTYPE", "CHEQUE"),
                        "Account Type must be CURRENT, SAVING, LOAN, MORTGAGE or ISA. Then press PF5."),
                Arguments.of(with(ok, "INTRT", ""), "Please supply a numeric interest rate then press PF5."),
                Arguments.of(with(with(ok, "ACTYPE", "CHEQUE"), "INTRT", ""),
                        "Please supply a numeric interest rate then press PF5."),
                Arguments.of(with(ok, "INTRT", "x"), "Please supply a numeric interest rate"),
                Arguments.of(with(ok, "INTRT", "1.2.3"), "Use one decimal point for interest rate only"),
                Arguments.of(with(ok, "INTRT", "1.234"), "Only up to two decimal places are supported"),
                Arguments.of(with(ok, "INTRT", "-1"), "Please supply a zero or positive interest rate"),
                Arguments.of(with(ok, "INTRT", "10000"), "Please supply an interest rate less than 9999.99%"),
                Arguments.of(with(with(ok, "ACTYPE", "LOAN"), "INTRT", "0000.00"),
                        "Interest rate cannot be 0 with this account type. Correct and press PF5."),
                Arguments.of(with(ok, "OVERDR", "x"), "Overdraft must be numeric. Correct and press PF5."),
                Arguments.of(with(ok, "LSTMTDD", "aa"), "Last statement date must be numeric"),
                Arguments.of(with(ok, "NSTMTMM", "b"), "Next statement date must be numeric"),
                Arguments.of(with(with(ok, "LSTMTDD", "31"), "LSTMTMM", "04"), "Incorrect date for LAST STATEMENT."),
                Arguments.of(with(ok, "LSTMTMM", "13"), "Incorrect date for LAST STATEMENT."),
                Arguments.of(with(with(ok, "NSTMTDD", "30"), "NSTMTMM", "02"), "Incorrect date for NEXT STATEMENT."));
    }

    @ParameterizedTest
    @MethodSource
    void bnk1uac(Map<String, String> fields, String expected) {
        assertThat(message(new UpdateAccountScreen(null), AidKey.PF5, fields)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"abc", "''", "123"})
    void bnk1uacLookupNeedsAccountNumber(String accno) {
        assertThat(message(new UpdateAccountScreen(null), AidKey.ENTER, f("ACCNO", accno)))
                .isEqualTo("Please enter an account number.");
    }

    @ParameterizedTest
    @CsvSource({"ENTER", "PF5"})
    void bnk1dacNeedsAccountNumber(AidKey aid) {
        assertThat(message(new DisplayAccountScreen(null), aid, f())).isEqualTo("Please enter an account number.");
    }

    @ParameterizedTest
    @CsvSource({"abc", "''", "123"})
    void bnk1ccaNeedsCustomerNumber(String custno) {
        assertThat(message(new ListAccountsScreen(null), AidKey.ENTER, f("CUSTNO", custno)))
                .isEqualTo("Please enter a customer number.");
    }

    @org.junit.jupiter.api.Test
    void bnk1ccaAccountLineLayout() {
        Account a = new Account("ACCT", 1, "987654", 1, "ISA", new BigDecimal("2.10"), LocalDate.of(2020, 1, 1), 0,
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 2, 1), new BigDecimal("-12.50"), new BigDecimal("623375.58"));
        assertThat(ListAccountsScreen.accountLine(a))
                .isEqualTo("987654      00000001         ISA            -0000000012.50  +0000623375.58");
    }
}
