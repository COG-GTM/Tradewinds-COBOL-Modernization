package com.ibm.cics.cip.bank.core.bankdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.domain.ControlRecord;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** BANKDATA port against baseline/seed/seed.sql (PARM '1,30,1,42', CBSA_RANDOM_SEED=42, 2026-01-15). */
class BankDataGeneratorTest {

    static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-15T10:30:00Z"), ZoneOffset.UTC);

    final BankDataGenerator generator = new BankDataGenerator("987654", FIXED, 42L);
    final BankDataSqlWriter writer = new BankDataSqlWriter();

    @Test
    void generatedSqlEqualsSeedSqlWithSeedActivityReversed() throws Exception {
        String generated = writer.toSql(generator.generate(1, 30, 1, 42));
        String expected = BaselineSeed.bankdataOnly(BaselineSeed.read());

        assertThat(BaselineSeed.normalise(generated)).isEqualTo(BaselineSeed.normalise(expected));
    }

    @Test
    void customerRowsEqualSeedSqlExactly() throws Exception {
        String generated = writer.toSql(generator.generate(1, 30, 1, 42));
        String seed = BaselineSeed.read();

        assertThat(BaselineSeed.lines(generated, "CUSTOMER")).hasSize(30)
                .containsExactlyElementsOf(BaselineSeed.lines(seed, "CUSTOMER"));
    }

    @Test
    void untouchedAccountRowsEqualSeedSqlExactly() throws Exception {
        List<String> generated = BaselineSeed.lines(writer.toSql(generator.generate(1, 30, 1, 42)), "ACCOUNT");
        List<String> seed = BaselineSeed.lines(BaselineSeed.read(), "ACCOUNT");
        // seed activity touches accounts 1, 2, 3, 4, 6, 10 (DBCRFUN/XFRFUN) and adds 74-76 (CREACC)
        List<Integer> touched = List.of(1, 2, 3, 4, 6, 10);

        assertThat(generated).hasSize(73);
        for (int n = 1; n <= 73; n++) {
            if (!touched.contains(n)) {
                assertThat(generated.get(n - 1)).as("account %d", n).isEqualTo(seed.get(n - 1));
            }
        }
    }

    @Test
    void sqlFormatMatchesSeedConventions() {
        String sql = writer.toSql(generator.generate(1, 30, 1, 42));

        assertThat(sql).startsWith(BankDataSqlWriter.HEADER).endsWith(");\n")
                .contains("INSERT INTO CUSTOMER (CUSTOMER_EYECATCHER, CUSTOMER_SORTCODE, CUSTOMER_NUMBER, "
                        + "CUSTOMER_NAME, CUSTOMER_ADDRESS, CUSTOMER_DATE_OF_BIRTH, CUSTOMER_CREDIT_SCORE, "
                        + "CUSTOMER_CS_REVIEW_DATE) VALUES ('CUST', '987654', '0000000001', 'Drs Adam N Renton', "
                        + "'18 Rowan Boulevard, Leicester', '1967-10-21', 397, '2026-02-04');")
                .contains("VALUES ('ACCT', '0000000001', '987654', '00000004', 'LOAN', 17.90, '1967-10-21', 0, "
                        + "'2021-07-01', '2021-08-01', -593118.54, -593118.54);")
                .contains("INSERT INTO CONTROL (CONTROL_NAME, CONTROL_VALUE_NUM, CONTROL_VALUE_STR) VALUES "
                        + "('987654-ACCOUNT-COUNT', 73, '');")
                .doesNotContain("INSERT INTO PROCTRAN");
    }

    @Test
    void controlRowsForDocumentedParm() {
        GeneratedBank bank = generator.generate(1, 30, 1, 42);

        assertThat(bank.controls()).containsExactly(
                new ControlRecord("987654-ACCOUNT-COUNT", 73L, ""),
                new ControlRecord("987654-ACCOUNT-LAST", 73L, ""),
                new ControlRecord("987654-CUSTOMER-COUNT", 30L, ""),
                new ControlRecord("987654-CUSTOMER-LAST", 30L, ""));
    }

    @Test
    void accountsUsePositionalTypesRatesAndOverdrafts() {
        GeneratedBank bank = generator.generate(1, 30, 1, 42);

        for (Customer c : bank.customers()) {
            List<Account> own = bank.accounts().stream().filter(a -> a.customerNumber() == c.customerNumber())
                    .toList();
            assertThat(own).hasSizeBetween(1, 4);
            for (int i = 0; i < own.size(); i++) {
                Account a = own.get(i);
                assertThat(a.accountType()).isEqualTo(BankDataTables.ACCOUNT_TYPES.get(i));
                assertThat(a.interestRate()).isEqualTo(BankDataTables.INTEREST_RATES.get(i));
                assertThat(a.overdraftLimit()).isEqualTo(BankDataTables.OVERDRAFT_LIMITS.get(i));
                assertThat(a.lastStatement()).isEqualTo(LocalDate.of(2021, 7, 1));
                assertThat(a.nextStatement()).isEqualTo(LocalDate.of(2021, 8, 1));
                assertThat(a.opened()).isEqualTo(c.dateOfBirth());
                assertThat(a.actualBalance()).isEqualTo(a.availableBalance());
                BigDecimal magnitude = a.availableBalance().abs();
                assertThat(magnitude).isBetween(new BigDecimal("1.00"), new BigDecimal("999998.99"));
                assertThat(a.availableBalance().signum()).isEqualTo("LOAN".equals(a.accountType()) ? -1 : 1);
            }
            assertThat(c.creditScoreReviewDate()).isBetween(LocalDate.of(2026, 1, 16), LocalDate.of(2026, 2, 4));
        }
        assertThat(bank.accounts()).extracting(Account::accountType).doesNotContain("MORTGAGE");
        assertThat(bank.accounts()).extracting(Account::accountNumber)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 73).boxed().toList());
    }

    @Test
    void stepKeyAndDeterminism() {
        GeneratedBank first = generator.generate(5, 20, 5, 7);
        GeneratedBank again = generator.generate(5, 20, 5, 7);

        assertThat(first).isEqualTo(again);
        assertThat(first.customers()).extracting(Customer::customerNumber).containsExactly(5L, 10L, 15L, 20L);
        assertThat(first.controls()).contains(new ControlRecord("987654-CUSTOMER-COUNT", 4L, ""),
                new ControlRecord("987654-CUSTOMER-LAST", 20L, ""));
        assertThat(first.accounts().get(0).accountNumber()).isEqualTo(1L);
        assertThat(generator.generate(5, 20, 5, 8)).isNotEqualTo(first);
        assertThat(new BankDataGenerator("987654", FIXED, 0L).generate(5, 20, 5, 7)).isNotEqualTo(first);
    }

    @Test
    void endBeforeStartRejectedBeforeZeroStep() {
        assertThatThrownBy(() -> generator.generate(10, 5, 0, 42)).isInstanceOfSatisfying(CbsaException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("Final customer number cannot be smaller than first customer number");
            assertThat(e.failCode()).isEqualTo("12");
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
    }

    @Test
    void zeroStepRejected() {
        assertThatThrownBy(() -> generator.generate(1, 5, 0, 42)).isInstanceOfSatisfying(CbsaException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("Gap between customers cannot be zero");
            assertThat(e.failCode()).isEqualTo("12");
        });
    }

    @Test
    void outOfRangeParmRejected() {
        assertThatThrownBy(() -> generator.generate(-1, 5, 1, 42)).isInstanceOf(CbsaException.class);
        assertThatThrownBy(() -> generator.generate(1, 10_000_000_000L, 1, 42)).isInstanceOf(CbsaException.class);
        assertThatThrownBy(() -> generator.generate(1, 5, 1, -42)).isInstanceOf(CbsaException.class);
    }

    @Test
    void randomSeedIsLeftJustifiedPic9of16() {
        assertThat(BankDataGenerator.randomSeedNumeric(42)).isEqualTo(4_200_000_000_000_000L);
        assertThat(BankDataGenerator.randomSeedNumeric(0)).isZero();
        assertThat(BankDataGenerator.randomSeedNumeric(1234567890123456789L)).isEqualTo(1234567890123456L);
    }

    @Test
    void randomMatchesJavaUtilRandomTruncatedToNineDecimals() {
        long seed = 42L + 4_200_000_000_000_000L;
        BankDataRandom legacy = new BankDataRandom(seed);
        Random reference = new Random(seed);
        for (int i = 0; i < 1000; i++) {
            assertThat(legacy.next9()).isEqualTo((long) (reference.nextDouble() * 1_000_000_000.0));
        }
    }
}
