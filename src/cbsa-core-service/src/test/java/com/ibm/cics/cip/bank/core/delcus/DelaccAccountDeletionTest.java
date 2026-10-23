package com.ibm.cics.cip.bank.core.delcus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

class DelaccAccountDeletionTest {

    /** Same shape as the T-08 {@code delacc.DeleteAccountService} public API. */
    public static class FakeDeleteAccountService {
        public Account deleteAccount(long accountNumber) {
            if (accountNumber == 404) {
                throw CbsaException.notFound("Account number 404 not found.", "1");
            }
            return new Account("ACCT", 1, "987654", accountNumber, "ISA", BigDecimal.ZERO, LocalDate.EPOCH,
                    0, LocalDate.EPOCH, LocalDate.EPOCH, BigDecimal.ONE, BigDecimal.ONE);
        }
    }

    private static GenericApplicationContext context() {
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.registerBean(FakeDeleteAccountService.class);
        ctx.refresh();
        return ctx;
    }

    @Test
    void productionBridgeTargetsTheT08ServiceName() {
        assertThat(DelaccAccountDeletion.DELETE_ACCOUNT_SERVICE)
                .isEqualTo("com.ibm.cics.cip.bank.core.delacc.DeleteAccountService");
    }

    @Test
    void delegatesToDeleteAccountService() {
        try (GenericApplicationContext ctx = context()) {
            AccountDeletion bridge = new DelaccAccountDeletion(ctx, FakeDeleteAccountService.class.getName());
            assertThat(bridge.deleteAccount(7).accountNumber()).isEqualTo(7);
        }
    }

    @Test
    void rethrowsServiceExceptionsUnwrapped() {
        try (GenericApplicationContext ctx = context()) {
            AccountDeletion bridge = new DelaccAccountDeletion(ctx, FakeDeleteAccountService.class.getName());
            assertThatThrownBy(() -> bridge.deleteAccount(404))
                    .isInstanceOf(CbsaException.class)
                    .hasMessage("Account number 404 not found.");
        }
    }

    @Test
    void missingServiceIsReportedClearly() {
        try (GenericApplicationContext ctx = context()) {
            AccountDeletion bridge = new DelaccAccountDeletion(ctx, "com.example.Missing");
            assertThatThrownBy(() -> bridge.deleteAccount(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("com.example.Missing#deleteAccount(long) is not available");
        }
    }
}
