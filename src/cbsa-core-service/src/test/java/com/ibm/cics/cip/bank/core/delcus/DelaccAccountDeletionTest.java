package com.ibm.cics.cip.bank.core.delcus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.delacc.DeleteAccountService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DelaccAccountDeletionTest {

    private static Account account(long number) {
        return new Account("ACCT", 1, "987654", number, "ISA", BigDecimal.ZERO, LocalDate.EPOCH,
                0, LocalDate.EPOCH, LocalDate.EPOCH, BigDecimal.ONE, BigDecimal.ONE);
    }

    @Test
    void delegatesToDeleteAccountService() {
        DeleteAccountService service = mock(DeleteAccountService.class);
        when(service.deleteAccount(7)).thenReturn(account(7));
        assertThat(new DelaccAccountDeletion(service).deleteAccount(7).accountNumber()).isEqualTo(7);
    }

    @Test
    void propagatesServiceExceptionsUnchanged() {
        DeleteAccountService service = mock(DeleteAccountService.class);
        CbsaException notFound = CbsaException.notFound("Account number 404 not found.", "1");
        when(service.deleteAccount(404)).thenThrow(notFound);
        assertThatThrownBy(() -> new DelaccAccountDeletion(service).deleteAccount(404)).isSameAs(notFound);
    }
}
