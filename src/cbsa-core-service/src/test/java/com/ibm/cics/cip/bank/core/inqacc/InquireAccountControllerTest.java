package com.ibm.cics.cip.bank.core.inqacc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** REST contract of INQACC / INQACCCU against the canonical seed (docs/migration/api-contract.md). */
@SpringBootTest(properties = {"cbsa.clock.fixed=2026-01-15T10:30:00Z", "cbsa.random.seed=42"})
@AutoConfigureMockMvc
@Transactional
class InquireAccountControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void accountJsonMatchesContractExactly() throws Exception {
        mvc.perform(get("/banking/account/1"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":"00000001","sortCode":"987654","customerNumber":"0000000001","accountType":"ISA",
                         "interestRate":2.10,"overdraft":0,"availableBalance":623375.58,"actualBalance":623375.58,
                         "dateOpened":"1967-10-21","lastStatementDate":"2021-07-01","nextStatementDate":"2021-08-01"}
                        """, true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"interestRate\":2.10")));
    }

    @Test
    void paddedPathParameterAccepted() throws Exception {
        mvc.perform(get("/banking/account/00000003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("00000003"))
                .andExpect(jsonPath("$.accountType").value("CURRENT"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"interestRate\":0.00")));
    }

    @Test
    void unknownAccountIs404WithLegacyMessage() throws Exception {
        mvc.perform(get("/banking/account/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorMessage").value("Account number 99999 not found."));
    }

    @Test
    void outOfRangeAccountNumbersAreNotFound() throws Exception {
        mvc.perform(get("/banking/account/0")).andExpect(status().isNotFound());
        mvc.perform(get("/banking/account/-5")).andExpect(status().isNotFound());
        mvc.perform(get("/banking/account/100000000")).andExpect(status().isNotFound());
    }

    @Test
    void nonNumericAccountIs400() throws Exception {
        mvc.perform(get("/banking/account/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").exists());
    }

    @Test
    void lastAccountSentinel() throws Exception {
        mvc.perform(get("/banking/account/99999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("00000076"))
                .andExpect(jsonPath("$.customerNumber").value("0000000020"))
                .andExpect(jsonPath("$.overdraft").value(500));
    }

    @Test
    void lastAccountOnEmptyTableAbendsHncs() throws Exception {
        jdbc.update("DELETE FROM ACCOUNT");
        mvc.perform(get("/banking/account/99999999"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.abendCode").value("HNCS"));
    }

    @Test
    void lastAccountWhenOnlyAccountZeroExists() throws Exception {
        jdbc.update("DELETE FROM ACCOUNT WHERE ACCOUNT_NUMBER <> '00000001'");
        jdbc.update("UPDATE ACCOUNT SET ACCOUNT_NUMBER = '00000000' WHERE ACCOUNT_NUMBER = '00000001'");
        mvc.perform(get("/banking/account/99999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("00000000"));
    }

    @Test
    void blankAccountTypeIsTreatedAsNotFound() throws Exception {
        jdbc.update("UPDATE ACCOUNT SET ACCOUNT_TYPE = '        ' WHERE ACCOUNT_NUMBER = '00000002'");
        mvc.perform(get("/banking/account/2")).andExpect(status().isNotFound());
    }

    @Test
    void accountsByCustomerOrderedByAccountNumber() throws Exception {
        mvc.perform(get("/banking/account/retrieveByCustomerNumber/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerFound").value(true))
                .andExpect(jsonPath("$.customerNumber").value("0000000005"))
                .andExpect(jsonPath("$.numberOfAccounts").value(2))
                .andExpect(jsonPath("$.accounts", hasSize(2)))
                .andExpect(jsonPath("$.accounts[0].id").value("00000016"))
                .andExpect(jsonPath("$.accounts[1].id").value("00000074"))
                .andExpect(jsonPath("$.accounts[1].accountType").value("MORTGAGE"))
                .andExpect(jsonPath("$.accounts[1].availableBalance").value(-150000.00));
    }

    @Test
    void accountsByCustomerCountOnly() throws Exception {
        mvc.perform(get("/banking/account/retrieveByCustomerNumber/5").param("countOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerFound").value(true))
                .andExpect(jsonPath("$.numberOfAccounts").value(2))
                .andExpect(jsonPath("$", not(hasKey("accounts"))));
    }

    @Test
    void customerWithoutAccounts() throws Exception {
        jdbc.update("DELETE FROM ACCOUNT WHERE ACCOUNT_CUSTOMER_NUMBER = '0000000030'");
        mvc.perform(get("/banking/account/retrieveByCustomerNumber/30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfAccounts").value(0))
                .andExpect(jsonPath("$.accounts", hasSize(0)));
    }

    @Test
    void unknownCustomerIs404FailCode1() throws Exception {
        for (String id : new String[] {"999", "0", "9999999999", "-1"}) {
            mvc.perform(get("/banking/account/retrieveByCustomerNumber/" + id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.failCode").value("1"))
                    .andExpect(jsonPath("$.errorMessage").value("Customer number " + Long.parseLong(id)
                            + " cannot be found."));
        }
        mvc.perform(get("/banking/account/retrieveByCustomerNumber/999").param("countOnly", "true"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failCode").value("1"));
    }

    @Test
    void listAccountsPaged() throws Exception {
        mvc.perform(get("/banking/account").param("limit", "3").param("offset", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfAccounts").value(3))
                .andExpect(jsonPath("$.accounts[0].id").value("00000003"))
                .andExpect(jsonPath("$.accounts[2].id").value("00000005"));
        mvc.perform(get("/banking/account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfAccounts").value(76))
                .andExpect(jsonPath("$.accounts", hasSize(76)));
    }

    @Test
    void listAccountsCountOnly() throws Exception {
        mvc.perform(get("/banking/account").param("countOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfAccounts").value(76))
                .andExpect(jsonPath("$", not(hasKey("accounts"))));
    }

    @Test
    void listAccountsRejectsNegativePaging() throws Exception {
        mvc.perform(get("/banking/account").param("limit", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/banking/account").param("offset", "-1")).andExpect(status().isBadRequest());
    }
}
