package com.ibm.cics.cip.bank.core.crecust;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * CRECUST {@code DATE-OF-BIRTH-CHECK / DOBC010} (R-C2), in the legacy order:
 * <ol>
 * <li>{@code COMM-BIRTH-YEAR < 1601} -> fail code {@code O};</li>
 * <li>{@code CEEDAYS} rejects the date (e.g. month 13, 30 February) -> {@code Z};</li>
 * <li>{@code CEELOCT} year minus birth year {@code > 150} -> {@code O};</li>
 * <li>today (Lilian) before the date of birth -> {@code Y}.</li>
 * </ol>
 */
public final class DateOfBirthValidator {

    public static final String FAIL_TOO_OLD = "O";
    public static final String FAIL_IN_FUTURE = "Y";
    public static final String FAIL_INVALID = "Z";

    static final int MIN_YEAR = 1601;
    static final int MAX_AGE = 150;

    private DateOfBirthValidator() {
    }

    /** Failure raised by DOBC010, with the fail code and the user-facing message. */
    public record Failure(String failCode, String message) {
    }

    public static Optional<Failure> check(int year, int month, int day, LocalDate today) {
        if (year < MIN_YEAR) {
            return Optional.of(new Failure(FAIL_TOO_OLD,
                    "Date of Birth year " + year + " is before " + MIN_YEAR));
        }
        LocalDate dob;
        try {
            dob = LocalDate.of(year, month, day);
        } catch (DateTimeException notADate) {
            return Optional.of(new Failure(FAIL_INVALID,
                    String.format("Date of Birth %02d/%02d/%04d is not a valid date", day, month, year)));
        }
        if (today.getYear() - year > MAX_AGE) {
            return Optional.of(new Failure(FAIL_TOO_OLD, "Customer is over 150 years old"));
        }
        if (today.isBefore(dob)) {
            return Optional.of(new Failure(FAIL_IN_FUTURE, "Date of Birth is in the future"));
        }
        return Optional.empty();
    }
}
