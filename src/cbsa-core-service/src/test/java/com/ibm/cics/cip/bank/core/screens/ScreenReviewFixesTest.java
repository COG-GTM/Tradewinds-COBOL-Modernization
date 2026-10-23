package com.ibm.cics.cip.bank.core.screens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;

@SpringBootTest(properties = {"cbsa.clock.fixed=2026-01-15T10:30:00Z", "cbsa.random.seed=42"})
class ScreenReviewFixesTest {

    @Autowired
    DisplayAccountScreen dac;
    @Autowired
    CreateAccountScreen cac;
    @Autowired
    JdbcTemplate jdbc;

    int accounts() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Integer.class);
    }

    @Test
    void failedLookupClearsStoredAccountSoPf5CannotDeleteIt() {
        ScreenOutput found = dac.handle(new ScreenInput(AidKey.ENTER, Map.of("ACCNO", "00000003"), Map.of()));
        assertThat(found.commarea()).containsEntry("ACCNO", "00000003");

        ScreenOutput missing = dac.handle(new ScreenInput(AidKey.ENTER, Map.of("ACCNO", "00099999"), found.commarea()));
        assertThat(missing.fields()).containsEntry("MESSAGE", DisplayAccountScreen.MSG_NOT_FOUND);
        assertThat(missing.commarea()).containsEntry("ACCNO", "00000000");

        int before = accounts();
        ScreenOutput pf5 = dac.handle(new ScreenInput(AidKey.PF5, Map.of(), missing.commarea()));
        assertThat(pf5.fields()).containsEntry("MESSAGE", "Please enter an account number.");
        assertThat(accounts()).isEqualTo(before);
    }

    @Test
    void unwiredServiceAbendPropagatesInsteadOfBecomingAFailMessage() {
        Map<String, String> ok = Map.of("CUSTNO", "0000000019", "ACCTYP", "SAVING  ", "INTRT", "1.25", "OVERDR", "0");
        assertThatThrownBy(() -> cac.handle(new ScreenInput(AidKey.ENTER, ok, Map.of())))
                .isInstanceOf(CbsaAbendException.class)
                .satisfies(e -> assertThat(((CbsaAbendException) e).abendCode()).isEqualTo("HBNK"));
    }
}
