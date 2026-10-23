package com.ibm.cics.cip.bank.core.updcust;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure COMMAREA-field helpers mirroring the COBOL MOVE / UNSTRING / blank tests. */
class UpdateCustomerServiceTest {

    @Test
    void titleIsFirstSpaceDelimitedTokenTruncatedToNine() {
        assertThat(UpdateCustomerService.title("Mr Ab Smith")).isEqualTo("Mr");
        assertThat(UpdateCustomerService.title("Professorial X")).isEqualTo("Professor");
        assertThat(UpdateCustomerService.title("")).isEmpty();
        assertThat(UpdateCustomerService.title(" Mr X")).isEmpty();
        assertThat(UpdateCustomerService.title("Lady")).isEqualTo("Lady");
    }

    @Test
    void validTitlesMatchTheCobolEvaluate() {
        assertThat(UpdateCustomerService.VALID_TITLES).containsExactlyInAnyOrder(
                "Professor", "Mr", "Mrs", "Miss", "Ms", "Dr", "Drs", "Lord", "Sir", "Lady", "");
    }

    @Test
    void blankMeansSpacesOrLeadingSpace() {
        assertThat(UpdateCustomerService.isBlank("")).isTrue();
        assertThat(UpdateCustomerService.isBlank("   ")).isTrue();
        assertThat(UpdateCustomerService.isBlank(" x")).isTrue();
        assertThat(UpdateCustomerService.isBlank("x ")).isFalse();
    }

    @Test
    void cobolFieldTruncatesAndMapsNullToSpaces() {
        assertThat(UpdateCustomerService.cobolField(null, 60)).isEmpty();
        assertThat(UpdateCustomerService.cobolField("abcdef", 3)).isEqualTo("abc");
        assertThat(UpdateCustomerService.cobolField("ab", 3)).isEqualTo("ab");
    }
}
