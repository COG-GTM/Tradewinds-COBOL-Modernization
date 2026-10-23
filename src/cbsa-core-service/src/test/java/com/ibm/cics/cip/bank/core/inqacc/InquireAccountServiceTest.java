package com.ibm.cics.cip.bank.core.inqacc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Public service API of INQACC / INQACCCU as named in docs/migration/program-map.md. */
@SpringBootTest(properties = {"cbsa.clock.fixed=2026-01-15T10:30:00Z", "cbsa.random.seed=42"})
@Transactional
class InquireAccountServiceTest {

    @Autowired
    InquireAccountService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void inquireReturnsSeedAccount() {
        Account a = service.inquire(74).orElseThrow();
        assertThat(a.customerNumber()).isEqualTo(5);
        assertThat(a.accountType()).isEqualTo("MORTGAGE");
        assertThat(a.interestRate()).isEqualByComparingTo("3.25");
        assertThat(a.opened()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(a.availableBalance()).isEqualByComparingTo(new BigDecimal("-150000.00"));
    }

    @Test
    void inquireLastAccount() {
        assertThat(service.inquire(InquireAccountService.LAST_ACCOUNT)).map(Account::accountNumber).contains(76L);
    }

    @Test
    void inquireMissingIsEmpty() {
        assertThat(service.inquire(99999)).isEmpty();
    }

    @Test
    void accountsForCustomerOrderedAndCapped() {
        assertThat(service.accountsForCustomer(1)).extracting(Account::accountNumber).isSorted();
        for (int i = 0; i < 25; i++) {
            jdbc.update("INSERT INTO ACCOUNT (ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER, ACCOUNT_SORTCODE, "
                    + "ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OPENED, ACCOUNT_OVERDRAFT_LIMIT, "
                    + "ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT, ACCOUNT_AVAILABLE_BALANCE, "
                    + "ACCOUNT_ACTUAL_BALANCE) VALUES ('ACCT', '0000000030', '987654', ?, 'SAVING', 1.00, "
                    + "'2026-01-15', 0, '2026-01-15', '2026-02-14', 0, 0)", String.format("%08d", 900 - i));
        }
        List<Account> accounts = service.accountsForCustomer(30);
        assertThat(accounts).hasSize(InquireAccountService.MAX_ACCOUNTS_PER_CUSTOMER);
        assertThat(accounts).extracting(Account::accountNumber).isSorted();
        assertThat(service.countAccountsForCustomer(30)).isEqualTo(InquireAccountService.MAX_ACCOUNTS_PER_CUSTOMER);
    }

    @Test
    void accountsForMissingCustomerThrowsFailCode1() {
        assertThatThrownBy(() -> service.accountsForCustomer(999))
                .isInstanceOfSatisfying(CbsaException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.failCode()).isEqualTo("1");
                });
    }
}
