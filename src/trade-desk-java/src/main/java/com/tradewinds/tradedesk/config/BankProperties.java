package com.tradewinds.tradedesk.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Replaces the SORTCODE copybook ({@code 77 SORTCODE PIC 9(6) VALUE 987654.}).
 */
@Validated
@ConfigurationProperties(prefix = "bank")
public record BankProperties(@NotNull @Pattern(regexp = "\\d{1,6}") String sortcode) {

    /** The sortcode as the PIC 9(6) value, i.e. zero-filled to 6 digits. */
    public String sortcodePic9() {
        return CobolText.moveToPic9(sortcode, 6);
    }
}
