package com.ibm.cics.cip.bank.core.creacc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StatementDatesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "2026-01-15, 2026-02-14",
            "2026-01-31, 2026-03-02",
            "2026-03-01, 2026-03-31",
            "2026-12-15, 2027-01-14",
            "2026-02-10, 2026-03-10",
            "2028-02-10, 2028-03-10",
            "2100-02-10, 2100-03-10",
            "2000-02-10, 2000-03-10",
            "2024-02-29, 2024-03-29",
            "2026-02-28, 2026-03-28"})
    void nextStatementFollowsRuleRA4(LocalDate opened, LocalDate expected) {
        assertThat(StatementDates.nextStatement(opened)).isEqualTo(expected);
    }

    @Test
    void gregorianLeapYears() {
        assertThat(StatementDates.isGregorianLeapYear(2024)).isTrue();
        assertThat(StatementDates.isGregorianLeapYear(2026)).isFalse();
        assertThat(StatementDates.isGregorianLeapYear(1900)).isFalse();
        assertThat(StatementDates.isGregorianLeapYear(2000)).isTrue();
    }
}
