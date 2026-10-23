package com.ibm.cics.cip.bank.core.crecust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.proctran.TaskNumberSupplier;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CreateCustomerServiceTest {

    private final CreditCheckService creditCheck = mock(CreditCheckService.class);
    private final CustomerWriter writer = mock(CustomerWriter.class);
    private final CreateCustomerService service = new CreateCustomerService(new TitleValidator(), creditCheck, writer,
            new TaskNumberSupplier(), new BankIdentity(TestSupport.properties(42L, false)), TestSupport.FIXED,
            TestSupport.properties(42L, false));

    @Test
    void invalidTitleFailsBeforeTheCreditCheck() {
        assertThatThrownBy(() -> service.createCustomer("Captain Jack Sparrow", "Tortuga", LocalDate.of(1980, 1, 1)))
                .isInstanceOfSatisfying(CbsaException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.failCode()).isEqualTo("T");
                    assertThat(e.getMessage()).isEqualTo("Customer title Captain is not valid");
                });
        verify(creditCheck, never()).check(any(), anyLong());
        verify(writer, never()).write(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void creditCheckFailureIsG500AndWritesNothing() {
        when(creditCheck.check(any(), anyLong()))
                .thenReturn(CreditCheckResult.failure("G", LocalDate.of(2026, 1, 15), List.of()));
        assertThatThrownBy(() -> service.createCustomer("Mr A B", "Leeds", LocalDate.of(1980, 1, 1)))
                .isInstanceOfSatisfying(CbsaException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(e.failCode()).isEqualTo("G");
                });
        verify(writer, never()).write(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void creditCheckRunsBeforeTheDateOfBirthCheck() {
        when(creditCheck.check(any(), anyLong()))
                .thenReturn(CreditCheckResult.failure("G", LocalDate.of(2026, 1, 15), List.of()));
        // invalid month AND failed credit check: CRECUST reports the credit check (G), not Z
        assertThatThrownBy(() -> service.createCustomer("Mr A B", "Leeds", 1990, 13, 15))
                .isInstanceOfSatisfying(CbsaException.class, e -> assertThat(e.failCode()).isEqualTo("G"));
    }

    @Test
    void invalidDateOfBirthAfterSuccessfulCreditCheckWritesNothing() {
        when(creditCheck.check(any(), anyLong()))
                .thenReturn(CreditCheckResult.success(500, LocalDate.of(2026, 1, 20), List.of()));
        assertThatThrownBy(() -> service.createCustomer("Mr A B", "Leeds", 1990, 13, 15))
                .isInstanceOfSatisfying(CbsaException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.failCode()).isEqualTo("Z");
                });
        verify(writer, never()).write(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void successWritesWithTheTaskClockAfterTheThreeSecondDelay() {
        CreditCheckResult ok = CreditCheckResult.success(673, LocalDate.of(2026, 1, 30), List.of());
        when(creditCheck.check(any(), anyLong())).thenReturn(ok);
        Customer saved = new Customer("CUST", "987654", 31, "Mr A B", "Leeds", LocalDate.of(1980, 1, 1), 673,
                LocalDate.of(2026, 1, 30));
        org.mockito.ArgumentCaptor<Clock> clock = org.mockito.ArgumentCaptor.forClass(Clock.class);
        when(writer.write(anyString(), anyString(), anyString(), any(), any(), clock.capture())).thenReturn(saved);

        assertThat(service.createCustomer("Mr A B", "Leeds", LocalDate.of(1980, 1, 1))).isSameAs(saved);
        assertThat(clock.getValue().instant()).isEqualTo(Instant.parse("2026-01-15T10:30:03Z"));
    }

    @Test
    void liveClockIsUsedAsIsForTheAuditTime() {
        Clock live = Clock.systemUTC();
        com.ibm.cics.cip.bank.core.common.config.CbsaProperties p = new com.ibm.cics.cip.bank.core.common.config.CbsaProperties(
                "987654", "x", new com.ibm.cics.cip.bank.core.common.config.CbsaProperties.Seed(List.of(), "never"),
                new com.ibm.cics.cip.bank.core.common.config.CbsaProperties.Clock(null, "UTC"),
                new com.ibm.cics.cip.bank.core.common.config.CbsaProperties.Random(null),
                new com.ibm.cics.cip.bank.core.common.config.CbsaProperties.Credit(false));
        CreateCustomerService liveService = new CreateCustomerService(new TitleValidator(), creditCheck, writer,
                new TaskNumberSupplier(), new BankIdentity(p), live, p);
        assertThat(liveService.auditClock()).isSameAs(live);
        assertThat(service.auditClock().instant()).isEqualTo(Instant.parse("2026-01-15T10:30:03Z"));
    }

    @Test
    void nameAndAddressAreTruncatedToTheCommareaWidths() {
        assertThat(CreateCustomerService.commField("x".repeat(70), 60)).hasSize(60);
        assertThat(CreateCustomerService.commField("Mr A  ", 60)).isEqualTo("Mr A");
    }
}
