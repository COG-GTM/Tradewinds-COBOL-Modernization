package com.tradewinds.tradedesk.dbcrfun.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.tradewinds.tradedesk.dbcrfun.dto.DbcrfunRequest;
import com.tradewinds.tradedesk.dbcrfun.dto.DbcrfunResponse;
import com.tradewinds.tradedesk.dbcrfun.dto.Origin;
import com.tradewinds.tradedesk.dbcrfun.entity.Account;
import com.tradewinds.tradedesk.dbcrfun.entity.AccountId;
import com.tradewinds.tradedesk.dbcrfun.entity.ProcTran;
import com.tradewinds.tradedesk.dbcrfun.repository.AccountRepository;
import com.tradewinds.tradedesk.dbcrfun.repository.ProcTranRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Runs the real transaction against H2 to prove the PROCTRAN-failure rollback. */
@SpringBootTest
@ActiveProfiles("local")
class DbcrfunServiceIntegrationTest {

    private static final Origin TELLER = new Origin("CBSAAPPL", "TELLER01", "TERM0001", "NETWORK1", 0);
    private static final AccountId ACCOUNT_1 = new AccountId("987654", "00000001");

    @Autowired
    private DbcrfunService service;
    @Autowired
    private AccountRepository accountRepository;
    @MockitoSpyBean
    private ProcTranRepository procTranRepository;

    @BeforeEach
    void resetBalance() {
        procTranRepository.deleteAll();
        Account account = accountRepository.findById(ACCOUNT_1).orElseThrow();
        account.setAvailableBalance(new BigDecimal("100.00"));
        account.setActualBalance(new BigDecimal("100.00"));
        accountRepository.save(account);
    }

    @Test
    void successCommitsAccountAndProcTran() {
        DbcrfunResponse response = service.process(new DbcrfunRequest("00000001", new BigDecimal("-30.00"), "987654", TELLER));

        assertThat(response.success()).isEqualTo("Y");
        Account account = accountRepository.findById(ACCOUNT_1).orElseThrow();
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("70.00");
        assertThat(account.getActualBalance()).isEqualByComparingTo("70.00");
        List<ProcTran> trans = procTranRepository.findAll();
        assertThat(trans).singleElement().satisfies(t -> {
            assertThat(t.getType()).isEqualTo("DEB");
            assertThat(t.getAmount()).isEqualByComparingTo("-30.00");
            assertThat(t.getEyecatcher()).isEqualTo("PRTR");
        });
    }

    @Test
    void procTranFailureRollsBackAccountUpdate() {
        doThrow(new DataIntegrityViolationException("insert failed"))
                .when(procTranRepository).saveAndFlush(any(ProcTran.class));

        DbcrfunResponse response = service.process(new DbcrfunRequest("00000001", new BigDecimal("-30.00"), "987654", TELLER));

        assertThat(response.success()).isEqualTo("N");
        assertThat(response.failCode()).isEqualTo("02");
        Account account = accountRepository.findById(ACCOUNT_1).orElseThrow();
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("100.00");
        assertThat(account.getActualBalance()).isEqualByComparingTo("100.00");
        assertThat(procTranRepository.count()).isZero();
    }
}
