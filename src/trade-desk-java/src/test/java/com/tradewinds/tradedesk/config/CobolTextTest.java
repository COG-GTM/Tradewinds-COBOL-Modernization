package com.tradewinds.tradedesk.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CobolTextTest {

    @Test
    void moveOfTwoCharLiteralToPicXKeepsOnlyLeftmostChar() {
        assertThat(CobolText.moveToPicX("02", 1)).isEqualTo("0");
    }

    @Test
    void moveToPicXPadsWithSpacesOnTheRight() {
        assertThat(CobolText.moveToPicX("TENDER", 8)).isEqualTo("TENDER  ");
        assertThat(CobolText.moveToPicX(null, 3)).isEqualTo("   ");
    }

    @Test
    void moveToPic9ZeroFillsOnTheLeftAndTruncatesHighOrderDigits() {
        assertThat(CobolText.moveToPic9("42", 8)).isEqualTo("00000042");
        assertThat(CobolText.moveToPic9("1234567", 6)).isEqualTo("234567");
    }
}
