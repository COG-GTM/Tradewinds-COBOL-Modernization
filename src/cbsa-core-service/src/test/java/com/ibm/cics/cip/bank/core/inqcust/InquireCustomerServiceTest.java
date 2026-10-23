package com.ibm.cics.cip.bank.core.inqcust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.config.CbsaProperties;
import com.ibm.cics.cip.bank.core.common.config.CbsaRandom;
import com.ibm.cics.cip.bank.core.common.config.CbsaRandomFactory;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.repository.CustomerRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** INQCUST paths the canonical seed cannot reach: empty file, random misses, vanishing last customer. */
class InquireCustomerServiceTest {

    private static final String SC = "987654";

    private CustomerRepository repo;
    private InquireCustomerService service;

    static CbsaProperties properties(Long seed) {
        return new CbsaProperties(SC, "CICS Bank Sample Application", null, null,
                new CbsaProperties.Random(seed), null);
    }

    static Customer customer(long number) {
        return new Customer(Customer.EYECATCHER, SC, number, "Mr Test " + number, "1 High St",
                LocalDate.of(1980, 1, 1), 500, LocalDate.of(2026, 2, 1));
    }

    @BeforeEach
    void setUp() {
        repo = mock(CustomerRepository.class);
        CbsaProperties props = properties(42L);
        service = new InquireCustomerService(repo, new BankIdentity(props), new CbsaRandomFactory(props));
    }

    @Test
    void ordinaryCustomerIsReadByKey() {
        when(repo.findByKey(SC, 5)).thenReturn(Optional.of(customer(5)));
        assertThat(service.inquire(5)).contains(customer(5));
        verify(repo, never()).maxCustomerNumber(SC);
    }

    @Test
    void randomCustomerFollowsLegacyFormulaOnTheSeededStream() {
        long expected = InquireCustomerService.randomCustomerNumber(30, new CbsaRandom(42L + InquireCustomerService.RANDOM_STREAM));
        assertThat(expected).isBetween(1L, 30L);
        when(repo.maxCustomerNumber(SC)).thenReturn(30L);
        when(repo.findByKey(SC, expected)).thenReturn(Optional.of(customer(expected)));
        assertThat(service.inquire(0)).contains(customer(expected));
    }

    @Test
    void randomCustomerRedrawsFromTheSameSequenceOnMiss() {
        CbsaRandom replica = new CbsaRandom(42L + InquireCustomerService.RANDOM_STREAM);
        long first = InquireCustomerService.randomCustomerNumber(1_000_000, replica);
        long second = InquireCustomerService.randomCustomerNumber(1_000_000, replica);
        when(repo.maxCustomerNumber(SC)).thenReturn(1_000_000L);
        when(repo.findByKey(eq(SC), anyLong())).thenReturn(Optional.empty());
        when(repo.findByKey(SC, second)).thenReturn(Optional.of(customer(second)));
        assertThat(first).isNotEqualTo(second);
        assertThat(service.inquire(0)).contains(customer(second));
    }

    @Test
    void randomCustomerGivesUpAfterThousandRetries() {
        when(repo.maxCustomerNumber(SC)).thenReturn(30L);
        when(repo.findByKey(eq(SC), anyLong())).thenReturn(Optional.empty());
        assertThat(service.inquire(0)).isEmpty();
        verify(repo, times(InquireCustomerService.MAX_RANDOM_RETRIES + 1)).findByKey(eq(SC), anyLong());
    }

    @Test
    void lastCustomerIsHighestNumber() {
        when(repo.maxCustomerNumber(SC)).thenReturn(30L);
        when(repo.findByKey(SC, 30)).thenReturn(Optional.of(customer(30)));
        assertThat(service.inquire(InquireCustomerService.LAST_CUSTOMER)).contains(customer(30));
    }

    @Test
    void lastCustomerBrowseIsRetriedOnceThenAbendsCvr1() {
        when(repo.maxCustomerNumber(SC)).thenReturn(30L, 29L);
        when(repo.findByKey(SC, 29)).thenReturn(Optional.of(customer(29)));
        assertThat(service.inquire(InquireCustomerService.LAST_CUSTOMER)).contains(customer(29));

        when(repo.maxCustomerNumber(SC)).thenReturn(30L);
        when(repo.findByKey(SC, 30)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.inquire(InquireCustomerService.LAST_CUSTOMER))
                .isInstanceOfSatisfying(CbsaAbendException.class, e -> assertThat(e.abendCode()).isEqualTo("CVR1"));
    }

    @Test
    void emptyCustomerFileGivesFailCode9ForSpecialNumbers() {
        when(repo.maxCustomerNumber(SC)).thenReturn(0L);
        for (long special : new long[] {0L, InquireCustomerService.LAST_CUSTOMER}) {
            assertThatThrownBy(() -> service.inquire(special))
                    .isInstanceOfSatisfying(CbsaException.class, e -> {
                        assertThat(e.failCode()).isEqualTo("9");
                        assertThat(e.status().value()).isEqualTo(404);
                    });
        }
    }

    @Test
    void dataStoreFailureAbendsCvr1() {
        when(repo.findByKey(SC, 7)).thenThrow(new DataAccessResourceFailureException("db down"));
        assertThatThrownBy(() -> service.inquire(7))
                .isInstanceOfSatisfying(CbsaAbendException.class, e -> {
                    assertThat(e.abendCode()).isEqualTo("CVR1");
                    assertThat(e.program()).isEqualTo("INQCUST");
                    assertThat(e.getMessage()).doesNotContain("db down");
                });
    }

    @Test
    void outOfRangeNumbersAreNotFoundWithoutTouchingTheStore() {
        assertThat(service.inquire(-1)).isEmpty();
        assertThat(service.inquire(InquireCustomerService.LAST_CUSTOMER + 1)).isEmpty();
        verify(repo, never()).findByKey(eq(SC), anyLong());
    }
}
