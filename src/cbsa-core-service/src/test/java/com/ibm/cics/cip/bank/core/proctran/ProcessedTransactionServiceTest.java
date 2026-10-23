package com.ibm.cics.cip.bank.core.proctran;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.ProcessedTransaction;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.repository.ProctranRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

class ProcessedTransactionServiceTest {

    private final ProctranRepository repository = mock(ProctranRepository.class);
    private final BankIdentity bank = mock(BankIdentity.class);
    private final ProcessedTransactionService service = new ProcessedTransactionService(repository, bank);

    @Test
    void unreadableTableIs500WithLibertyMessage() {
        when(bank.sortCode()).thenReturn("987654");
        when(repository.findBySortCode(anyString(), anyInt(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("down"));
        assertThatThrownBy(() -> service.list(10, 0))
                .isInstanceOf(CbsaException.class)
                .hasMessage(ProcessedTransactionService.TABLE_NOT_ACCESSIBLE)
                .satisfies(e -> assertThat(((CbsaException) e).status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Test
    void malformedDescriptionsAreNotDecoded() {
        ProcessedTransactionJson json = ProcessedTransactionJson.of(new ProcessedTransaction("PRTR", "987654",
                "00000000", LocalDate.of(2026, 1, 10), "0900", "000000000001", "OCC", "SHORT", BigDecimal.ZERO));
        assertThat(json.customer()).isNull();
        assertThat(json.dateOfBirth()).isNull();
        assertThat(json.time()).isEqualTo("0900");
        assertThat(json.amount()).isEqualByComparingTo("0.00");

        ProcessedTransactionJson badDate = ProcessedTransactionJson.of(new ProcessedTransaction("PRTR", "987654",
                "00000001", LocalDate.of(2026, 1, 10), "090000", "000000000001", "OCA",
                "0000000005MORTGAGE99999999XXXXXXXX", BigDecimal.ZERO));
        assertThat(badDate.accountType()).isNull();
        assertThat(badDate.lastStatement()).isNull();
    }
}
