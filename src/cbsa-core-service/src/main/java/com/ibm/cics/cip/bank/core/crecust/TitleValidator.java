package com.ibm.cics.cip.bank.core.crecust;

import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * CRECUST {@code PREMIERE / P010} title rule (R-C1): {@code UNSTRING COMM-NAME DELIMITED BY SPACE INTO
 * WS-UNSTR-TITLE} (PIC X(9)) followed by the {@code EVALUATE WS-UNSTR-TITLE}. The comparison is case-sensitive,
 * the first word is truncated to 9 characters and a blank title (name starting with a space) is accepted.
 */
@Component
public class TitleValidator {

    /** WS-UNSTR-TITLE is PIC X(9). */
    static final int TITLE_WIDTH = 9;

    static final Set<String> VALID_TITLES =
            Set.of("Professor", "Mr", "Mrs", "Miss", "Ms", "Dr", "Drs", "Lord", "Sir", "Lady", "");

    /** First space-delimited word of {@code name}, as the UI and the error message show it. */
    public static String firstWord(String name) {
        if (name == null) {
            return "";
        }
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }

    /** The value WS-UNSTR-TITLE holds after the UNSTRING (first word truncated to PIC X(9), trailing blanks off). */
    static String unstringTitle(String name) {
        String word = firstWord(name);
        if (word.length() > TITLE_WIDTH) {
            word = word.substring(0, TITLE_WIDTH);
        }
        return word.stripTrailing();
    }

    public boolean isValid(String name) {
        return VALID_TITLES.contains(unstringTitle(name));
    }
}
