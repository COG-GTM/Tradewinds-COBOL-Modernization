package com.tradewinds.tradedesk.dbcrfun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;

import com.tradewinds.tradedesk.config.BankProperties;

/** Branches that need a repository failure a real database cannot easily produce. */
@ExtendWith(MockitoExtension.class)
class DbcrfunServiceMockTest {

    @Mock
    AccountRepository accountRepository;

    @Mock
    ProcTranRepository procTranRepository;

    DbcrfunService service;

    @BeforeEach
    void setUp() {
        service = new DbcrfunService(accountRepository, procTranRepository, new BankProperties("987654"),
                new TaskNumberSource(), Clock.systemUTC());
    }

    @Test
    void selectConnectionLossReturnsFailCode2AndIsAStormDrainCondition() {
        CannotGetJdbcConnectionException lost = new CannotGetJdbcConnectionException("connection lost");
        when(accountRepository.findById(any())).thenThrow(lost);

        DbcrfunResponse response = service.applyTransaction(request("-1.00", 0));

        assertThat(response.success()).isEqualTo("N");
        assertThat(response.failCode()).isEqualTo("2");
        assertThat(DbcrfunService.checkForStormDrain(lost)).isTrue();
    }

    @Test
    void stormDrainMatchesSqlcode923AndSqlstate08Only() {
        assertThat(DbcrfunService.isConnectionLost(
                new UncategorizedSQLException("select", "sql", new SQLException("lost", "57000", -923)))).isTrue();
        assertThat(DbcrfunService.isConnectionLost(
                new UncategorizedSQLException("select", "sql", new SQLException("comm", "08001", -30081)))).isTrue();
        assertThat(DbcrfunService.isConnectionLost(new CannotAcquireLockException("deadlock"))).isFalse();
        assertThat(DbcrfunService.isConnectionLost(new DataIntegrityViolationException("dup"))).isFalse();
    }

    // L416-427: the new balances are moved to the COMMAREA before the UPDATE SQLCODE is checked.
    @Test
    void accountUpdateFailureReturnsFailCode2WithComputedBalances() {
        when(accountRepository.findById(any())).thenReturn(Optional.of(account("CURRENT", "100.00")));
        when(accountRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("update"));

        DbcrfunResponse response = service.applyTransaction(request("-10.00", 0));

        assertThat(response.success()).isEqualTo("N");
        assertThat(response.failCode()).isEqualTo("2");
        assertThat(response.avBal()).isEqualByComparingTo("90.00");
        assertThat(response.actBal()).isEqualByComparingTo("90.00");
        verify(procTranRepository, never()).saveAndFlush(any());
    }

    @Test
    void proctranInsertFailureReturnsNWithSingleCharFailCode0() {
        when(accountRepository.findById(any())).thenReturn(Optional.of(account("CURRENT", "100.00")));
        when(procTranRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("insert"));

        DbcrfunResponse response = service.applyTransaction(request("10.00", 0));

        assertThat(response.success()).isEqualTo("N");
        assertThat(response.failCode()).isEqualTo("0");
    }

    // L330/L368: ACCOUNT_TYPE is PIC X(8); the literal 'TENDER  ' matches an unpadded 'TENDER'.
    @Test
    void accountTypeComparisonIsSpacePaddedToEightChars() {
        assertThat(DbcrfunService.isRestrictedPayment(account("TENDER", "0"), 496)).isTrue();
        assertThat(DbcrfunService.isRestrictedPayment(account("TENDER  ", "0"), 496)).isTrue();
        assertThat(DbcrfunService.isRestrictedPayment(account("HOSPITAL", "0"), 496)).isTrue();
        assertThat(DbcrfunService.isRestrictedPayment(account(" TENDER", "0"), 496)).isFalse();
        assertThat(DbcrfunService.isRestrictedPayment(account("hospital", "0"), 496)).isFalse();
        assertThat(DbcrfunService.isRestrictedPayment(account("HOSPITAL", "0"), 0)).isFalse();
    }

    @Test
    void amountWithMoreThanTwoDecimalsIsTruncatedNotRounded() {
        when(accountRepository.findById(any())).thenReturn(Optional.of(account("CURRENT", "100.00")));

        DbcrfunResponse response = service.applyTransaction(request("1.009", 0));

        assertThat(response.avBal()).isEqualByComparingTo("101.00");
    }

    private static DbcrfunRequest request(String amt, int facilType) {
        return new DbcrfunRequest("00000042", new BigDecimal(amt), null,
                new DbcrfunOrigin("CBSAAPPL", "TELLER01", null, null, facilType));
    }

    private static Account account(String type, String balance) {
        Account account = new Account();
        account.setSortcode("987654");
        account.setNumber("00000042");
        account.setType(type);
        account.setAvailableBalance(new BigDecimal(balance));
        account.setActualBalance(new BigDecimal(balance));
        return account;
    }
}
