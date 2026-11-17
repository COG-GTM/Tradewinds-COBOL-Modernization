package com.tradewinds.tradedesk.dbcrfun.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradewinds.tradedesk.dbcrfun.dto.DbcrfunRequest;
import com.tradewinds.tradedesk.dbcrfun.dto.DbcrfunResponse;
import com.tradewinds.tradedesk.dbcrfun.service.DbcrfunService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DbcrfunController.class)
class DbcrfunControllerTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private DbcrfunService service;

    @Test
    void postDelegatesToService() throws Exception {
        when(service.process(any(DbcrfunRequest.class)))
                .thenReturn(DbcrfunResponse.success(new BigDecimal("89.50"), new BigDecimal("89.50")));

        mvc.perform(post("/api/dbcrfun").contentType(MediaType.APPLICATION_JSON).content("""
                        {"accno":"00000001","amt":-10.50,"sortc":"987654",
                         "origin":{"applid":"CBSAAPPL","userid":"TELLER01","facilityName":"TERM0001",
                                   "netwrkId":"NETWORK1","faciltype":0}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value("Y"))
                .andExpect(jsonPath("$.failCode").value("0"))
                .andExpect(jsonPath("$.avBal").value(89.50))
                .andExpect(jsonPath("$.actBal").value(89.50));
    }

    @Test
    void missingAmountIsBadRequest() throws Exception {
        mvc.perform(post("/api/dbcrfun").contentType(MediaType.APPLICATION_JSON).content("{\"accno\":\"00000001\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
