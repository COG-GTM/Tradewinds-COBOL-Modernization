package com.ibm.cics.cip.bank.core.crecust;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DateOfBirthValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);

    @ParameterizedTest
    @CsvSource({
        "1491, 6, 28, O",   // before 1601 (checked before CEEDAYS)
        "1600, 13, 40, O",  // year rule wins over an impossible date
        "1990, 13, 15, Z",  // CEEDAYS: month 13
        "2023, 2, 29, Z",   // CEEDAYS: not a leap year
        "1990, 0, 1, Z",
        "1800, 1, 1, O",    // 2026 - 1800 > 150
        "1875, 12, 31, O",  // only 150 years by date, but CRECUST compares years: 2026 - 1875 = 151
        "1876, 1, 1, ''",   // 150 years: accepted
        "2030, 1, 1, Y",    // in the future
        "2026, 1, 16, Y",
        "2026, 1, 15, ''",  // born today: accepted
        "1912, 6, 23, ''"})
    void legacyOrderAndFailCodes(int year, int month, int day, String expected) {
        Optional<DateOfBirthValidator.Failure> failure = DateOfBirthValidator.check(year, month, day, TODAY);
        String code = failure.map(DateOfBirthValidator.Failure::failCode).orElse("");
        assertThat(code).isEqualTo(expected);
    }
}
