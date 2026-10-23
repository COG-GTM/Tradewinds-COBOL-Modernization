package com.ibm.cics.cip.bank.core.crecust;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TitleValidatorTest {

    private final TitleValidator validator = new TitleValidator();

    @ParameterizedTest
    @ValueSource(strings = {"Professor Brian Cox", "Mr Alan T Turing", "Mrs Ada L Lovelace", "Miss A B", "Ms Jane Doe",
        "Dr Grace B Hopper", "Drs X Y", "Lord Byron", "Sir Isaac Newton", "Lady Jane Grey", " Leading Space",
        "Professorial Truncated To Nine", "Mr"})
    void legacyTitlesAreAccepted(String name) {
        assertThat(validator.isValid(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Captain Jack Sparrow", "mr lower case", "MR UPPER", "Mister Smith", "Rev Green",
        "Mrs. Dotted", "Jane"})
    void otherTitlesAreRejected(String name) {
        assertThat(validator.isValid(name)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void firstWordIsWhatTheErrorMessageShows() {
        assertThat(TitleValidator.firstWord("Captain Jack Sparrow")).isEqualTo("Captain");
        assertThat(TitleValidator.firstWord("Single")).isEqualTo("Single");
        assertThat(TitleValidator.unstringTitle("Professorial X")).isEqualTo("Professor");
    }
}
