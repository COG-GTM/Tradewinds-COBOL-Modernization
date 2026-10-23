package com.ibm.cics.cip.bank.core.updacc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** UPDACC business rules ({@code UPDATE-ACCOUNT-DB2}) against H2 loaded from the canonical seed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42",
        "spring.datasource.url=jdbc:h2:mem:cbsa-updacc;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
@AutoConfigureMockMvc
class UpdateAccountServiceTest {

    @Autowired
    UpdateAccountService service;

    @Autowired
    AccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void reset() {
        UpdaccTestDatabase.resetToSeed(jdbc, dataSource);
    }

    @Test
    void updatesOnlyTypeRateAndOverdraft() {
        Account before = accounts.findByKey("987654", 2).orElseThrow();
        Map<String, List<Map<String, Object>>> snap = UpdaccTestDatabase.snapshot(jdbc);

        Account result = service.updateAccount(2, "CURRENT", new BigDecimal("0.5"), 300);

        Account stored = accounts.findByKey("987654", 2).orElseThrow();
        assertThat(result).isEqualTo(stored);
        assertThat(stored.accountType()).isEqualTo("CURRENT");
        assertThat(stored.interestRate()).isEqualByComparingTo("0.50");
        assertThat(stored.overdraftLimit()).isEqualTo(300);
        assertThat(stored).isEqualTo(before.withTypeRateOverdraft("CURRENT", new BigDecimal("0.50"), 300));
        Map<String, Map<String, List<Object>>> delta =
                UpdaccTestDatabase.delta(snap, UpdaccTestDatabase.snapshot(jdbc));
        assertThat(delta.get("PROCTRAN").values()).allMatch(List::isEmpty);
        assertThat(delta.get("CONTROL").values()).allMatch(List::isEmpty);
        assertThat(delta.get("ACCOUNT").get("updated")).hasSize(1);
    }

    @Test
    void notFoundIs404AndChangesNothing() {
        CbsaException e = failure(() -> service.updateAccount(99999, "SAVING", BigDecimal.ONE, 0));
        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.getMessage()).isEqualTo("Account number 99999 not found.");
    }

    @Test
    void blankOrLeadingSpaceTypeIs400() {
        for (String type : new String[] {"", "        ", " ISA", null}) {
            Map<String, List<Map<String, Object>>> snap = UpdaccTestDatabase.snapshot(jdbc);
            CbsaException e = failure(() -> service.updateAccount(5, type, BigDecimal.ONE, 0));
            assertThat(e.status()).as("type '%s'", type).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(UpdaccTestDatabase.snapshot(jdbc)).isEqualTo(snap);
        }
    }

    @Test
    void notFoundIsCheckedBeforeBlankType() {
        // UAD010: the SELECT runs before the account-type check.
        assertThat(failure(() -> service.updateAccount(99999, " ", BigDecimal.ONE, 0)).status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void typeIsTruncatedToPicX8AndRateToTwoDecimals() {
        Account a = service.updateAccount(6, "SAVINGSXYZ", new BigDecimal("1.239"), 10);
        assertThat(a.accountType()).isEqualTo("SAVINGSX");
        assertThat(a.interestRate()).isEqualByComparingTo("1.23");
        assertThat(accounts.findByKey("987654", 6).orElseThrow().accountType()).isEqualTo("SAVINGSX");
    }

    @Test
    void valuesOutsideCommareaPicturesAre400() {
        assertThat(failure(() -> service.updateAccount(6, "ISA", new BigDecimal("-0.01"), 0)).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(failure(() -> service.updateAccount(6, "ISA", new BigDecimal("10000.00"), 0)).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(failure(() -> service.updateAccount(6, "ISA", null, 0)).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(failure(() -> service.updateAccount(6, "ISA", BigDecimal.ONE, -1)).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(failure(() -> service.updateAccount(6, "ISA", BigDecimal.ONE, 100_000_000)).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(accounts.findByKey("987654", 6).orElseThrow().accountType()).isEqualTo("SAVING");
    }

    @Test
    void boundaryValuesAccepted() {
        Account a = service.updateAccount(7, "CURRENT", new BigDecimal("9999.99"), 99_999_999);
        assertThat(a.interestRate()).isEqualByComparingTo("9999.99");
        assertThat(a.overdraftLimit()).isEqualTo(99_999_999);
        Account z = service.updateAccount(7, "CURRENT", BigDecimal.ZERO, 0);
        assertThat(z.interestRate()).isEqualByComparingTo("0");
        assertThat(z.overdraftLimit()).isZero();
    }

    private static CbsaException failure(Executable call) {
        CbsaException e = catchThrowableOfType(() -> {
            try {
                call.execute();
            } catch (Throwable t) {
                throw t instanceof Exception ex ? ex : new RuntimeException(t);
            }
        }, CbsaException.class);
        assertThat(e).isNotNull();
        return e;
    }
}
