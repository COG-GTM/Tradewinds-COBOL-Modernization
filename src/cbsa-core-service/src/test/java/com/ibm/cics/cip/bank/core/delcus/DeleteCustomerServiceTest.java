package com.ibm.cics.cip.bank.core.delcus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.domain.ProctranType;
import com.ibm.cics.cip.bank.core.common.error.CbsaAbendException;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;

class DeleteCustomerServiceTest extends DelcusTestSupport {

    @Autowired
    DeleteCustomerService service;

    @Test
    void deletesCustomerAndAllAccountsReturningCustomerJson() throws Exception {
        jdbc.update("DELETE FROM PROCTRAN");
        mvc.perform(delete("/banking/customer/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("0000000001"))
                .andExpect(jsonPath("$.sortCode").value("987654"))
                .andExpect(jsonPath("$.customerName").isString())
                .andExpect(jsonPath("$.customerCreditScore").isString())
                .andExpect(jsonPath("$.dateOfBirth").isString());

        assertThat(delacc.calls()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(count("SELECT COUNT(*) FROM CUSTOMER WHERE CUSTOMER_NUMBER = '0000000001'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_CUSTOMER_NUMBER = '0000000001'")).isZero();
        List<String> types = jdbc.queryForList("SELECT PROCTRAN_TYPE FROM PROCTRAN ORDER BY _ROWID_", String.class);
        assertThat(types).containsExactly("ODA", "ODA", "ODA", "ODA", "ODC");
    }

    @Test
    void serviceReturnsDeletedCustomerRecord() {
        Customer c = service.deleteCustomer(30);
        assertThat(c.customerNumber()).isEqualTo(30);
        assertThat(c.sortCode()).isEqualTo("987654");
        assertThat(c.dateOfBirth()).isEqualTo(LocalDate.of(1973, 5, 18));
    }

    @Test
    void missingCustomerIs404FailCode1AndChangesNothing() throws Exception {
        long proctran = count("SELECT COUNT(*) FROM PROCTRAN");
        mvc.perform(delete("/banking/customer/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failCode").value("1"))
                .andExpect(jsonPath("$.errorMessage").value("Customer number 999 not found."));
        assertThat(delacc.calls()).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM PROCTRAN")).isEqualTo(proctran);
    }

    @Test
    void negativeCustomerNumberIs404() throws Exception {
        mvc.perform(delete("/banking/customer/-1")).andExpect(status().isNotFound());
        assertThat(delacc.calls()).isEmpty();
    }

    @Test
    void proctranFailureAbendsHwptAndRollsBackEverything() throws Exception {
        doThrow(new DataAccessResourceFailureException("PROCTRAN down"))
                .when(proctranWriter).deleteCustomer(eq(ProctranType.BRANCH_DELETE_CUSTOMER), any());
        long accounts = count("SELECT COUNT(*) FROM ACCOUNT");
        long proctran = count("SELECT COUNT(*) FROM PROCTRAN");

        mvc.perform(delete("/banking/customer/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.abendCode").value("HWPT"));

        assertThat(delacc.calls()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(count("SELECT COUNT(*) FROM CUSTOMER WHERE CUSTOMER_NUMBER = '0000000001'")).isOne();
        assertThat(count("SELECT COUNT(*) FROM ACCOUNT")).isEqualTo(accounts);
        assertThat(count("SELECT COUNT(*) FROM PROCTRAN")).isEqualTo(proctran);
    }

    @Test
    void proctranFailureThrowsCbsaAbendExceptionFromService() {
        doThrow(new DataAccessResourceFailureException("PROCTRAN down"))
                .when(proctranWriter).deleteCustomer(any(), any());
        assertThatThrownBy(() -> service.deleteCustomer(30))
                .isInstanceOf(CbsaAbendException.class)
                .extracting(e -> ((CbsaAbendException) e).abendCode()).isEqualTo("HWPT");
        assertThat(count("SELECT COUNT(*) FROM CUSTOMER WHERE CUSTOMER_NUMBER = '0000000030'")).isOne();
    }

    @Test
    void accountNotFoundDuringDeleteRollsBackEarlierAccounts() throws Exception {
        delacc.failOn(3, CbsaException.notFound("Account number 3 not found.", "1"));
        long accounts = count("SELECT COUNT(*) FROM ACCOUNT");
        long proctran = count("SELECT COUNT(*) FROM PROCTRAN");

        mvc.perform(delete("/banking/customer/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failCode").value("1"))
                .andExpect(jsonPath("$.errorMessage").value("Error deleting account 3 for customer 1,account not found"));

        assertThat(delacc.calls()).containsExactly(1L, 2L, 3L);
        assertThat(count("SELECT COUNT(*) FROM ACCOUNT")).isEqualTo(accounts);
        assertThat(count("SELECT COUNT(*) FROM PROCTRAN")).isEqualTo(proctran);
        assertThat(count("SELECT COUNT(*) FROM CUSTOMER WHERE CUSTOMER_NUMBER = '0000000001'")).isOne();
    }

    @Test
    void accountAbendPropagatesAndRollsBack() throws Exception {
        delacc.failOn(2, new CbsaAbendException("HWPT", "DELACC", "PROCTRAN down"));
        long accounts = count("SELECT COUNT(*) FROM ACCOUNT");

        mvc.perform(delete("/banking/customer/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.abendCode").value("HWPT"));

        assertThat(count("SELECT COUNT(*) FROM ACCOUNT")).isEqualTo(accounts);
        assertThat(count("SELECT COUNT(*) FROM CUSTOMER WHERE CUSTOMER_NUMBER = '0000000001'")).isOne();
    }
}
