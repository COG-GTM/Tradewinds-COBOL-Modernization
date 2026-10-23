package com.ibm.cics.cip.bank.core.abndproc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The ABNDPROC admin endpoint is not registered by default. */
@SpringBootTest
@AutoConfigureMockMvc
class AbendRecordEndpointDisabledTest {

    @Autowired
    MockMvc mvc;

    @Test
    void absentByDefault() throws Exception {
        mvc.perform(post("/banking/admin/abend").header(AbendRecordController.TOKEN_HEADER, "")
                .contentType(MediaType.APPLICATION_JSON).content("{\"utimeKey\":1}")).andExpect(status().isNotFound());
    }
}
