package com.ibm.cics.cip.bank.core.inqcust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HTTP contract of the T-02 customer read endpoints (docs/migration/api-contract.md) on the canonical seed. */
@SpringBootTest(properties = {
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42"})
@AutoConfigureMockMvc
class InquireCustomerControllerTest {

    private static final String CONTEXT = "/webui-1.0";
    private static final String BASE = CONTEXT + "/banking/customer";

    @Autowired
    MockMvc mvc;

    private ResultActions call(String pathAndQuery) throws Exception {
        return mvc.perform(get(BASE + pathAndQuery).contextPath(CONTEXT));
    }

    @Test
    void existingCustomerReturnsContractJson() throws Exception {
        call("/1").andExpect(status().isOk()).andExpect(content().json("""
                {"id":"0000000001","sortCode":"987654","customerName":"Drs Adam N Renton",
                 "customerAddress":"18 Rowan Boulevard, Leicester","dateOfBirth":"1967-10-21",
                 "customerCreditScore":"397","customerCreditScoreReviewDate":"2026-02-04"}
                """, true));
    }

    @Test
    void creditScoreIsAStringAndPaddedPathIsAccepted() throws Exception {
        call("/0000000017").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("0000000017"))
                .andExpect(jsonPath("$.customerCreditScore").value(Matchers.instanceOf(String.class)))
                .andExpect(jsonPath("$.customerCreditScore").value("507"));
    }

    @Test
    void missingCustomerIs404WithFailCode1() throws Exception {
        call("/999").andExpect(status().isNotFound()).andExpect(content().json("""
                {"errorMessage":"Customer number 999 not found.","failCode":"1"}
                """, true));
    }

    @Test
    void customerNumberBeyondPic9x10IsNotFound() throws Exception {
        call("/12345678901").andExpect(status().isNotFound()).andExpect(jsonPath("$.failCode").value("1"));
    }

    @Test
    void negativeCustomerNumberIs400() throws Exception {
        call("/-1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage").value("Customer number cannot be negative"));
    }

    @Test
    void nonNumericCustomerNumberIs400() throws Exception {
        call("/abc").andExpect(status().isBadRequest());
    }

    @Test
    void customerZeroReturnsADeterministicExistingCustomer() throws Exception {
        String first = call("/0").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Matchers.matchesPattern("\\d{10}")))
                .andReturn().getResponse().getContentAsString();
        String second = call("/0").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void customer9999999999ReturnsLastCustomer() throws Exception {
        call("/9999999999").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("0000000030"))
                .andExpect(jsonPath("$.customerName").value("Professor Kimberley H Jones"));
    }

    @Test
    void nameSearchIsCaseInsensitiveContainsOrderedByNumber() throws Exception {
        call("/name?name=JONES&limit=10").andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfCustomers").value(Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.customers[*].customerName",
                        Matchers.everyItem(Matchers.containsStringIgnoringCase("jones"))))
                .andExpect(jsonPath("$.customers[-1:].id").value(Matchers.hasItem("0000000030")));
        call("/name?name=renton").andExpect(status().isOk())
                .andExpect(jsonPath("$.customers[0].id").value("0000000001"));
    }

    @Test
    void nameSearchHonoursLimitAndOffset() throws Exception {
        String all = call("/name?name=e").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int total = com.jayway.jsonpath.JsonPath.read(all, "$.numberOfCustomers");
        assertThat(total).isGreaterThan(3);
        String secondId = com.jayway.jsonpath.JsonPath.read(all, "$.customers[1].id");
        call("/name?name=e&limit=2&offset=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfCustomers").value(2))
                .andExpect(jsonPath("$.customers[0].id").value(secondId));
        call("/name?name=e&countOnly=true").andExpect(status().isOk())
                .andExpect(content().json("{\"numberOfCustomers\":" + total + "}", true));
    }

    @Test
    void nameSearchWithoutMatchesIs404() throws Exception {
        call("/name?name=zzzz-nobody").andExpect(status().isNotFound());
        call("/name?name=%25").andExpect(status().isNotFound());
        call("/name?name=zzzz-nobody&countOnly=true").andExpect(status().isOk())
                .andExpect(content().json("{\"numberOfCustomers\":0}", true));
    }

    @Test
    void nameSearchValidation() throws Exception {
        call("/name").andExpect(status().isBadRequest());
        call("/name?name=a&limit=-1").andExpect(status().isBadRequest());
        call("/name?name=a&offset=-1").andExpect(status().isBadRequest());
    }

    @Test
    void listAndCountCustomers() throws Exception {
        call("").andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfCustomers").value(30))
                .andExpect(jsonPath("$.customers[0].id").value("0000000001"))
                .andExpect(jsonPath("$.customers[29].id").value("0000000030"));
        call("?limit=5&offset=10").andExpect(status().isOk())
                .andExpect(jsonPath("$.numberOfCustomers").value(5))
                .andExpect(jsonPath("$.customers[*].id").value(Matchers.contains(
                        "0000000011", "0000000012", "0000000013", "0000000014", "0000000015")));
        call("?limit=0").andExpect(status().isOk()).andExpect(jsonPath("$.numberOfCustomers").value(30));
        call("?countOnly=true").andExpect(status().isOk())
                .andExpect(content().json("{\"numberOfCustomers\":30}", true));
        call("?offset=-3").andExpect(status().isBadRequest());
    }
}
