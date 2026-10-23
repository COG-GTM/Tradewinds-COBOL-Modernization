package com.ibm.cics.cip.bank.core.crecust;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The five credit agencies (transactions OCR1..OCR5 running CRDTAGY1..5). */
@Configuration
public class CreditAgencyConfig {

    @Bean(name = "CRDTAGY1")
    public CreditAgency crdtagy1() {
        return new CreditAgency(1);
    }

    @Bean(name = "CRDTAGY2")
    public CreditAgency crdtagy2() {
        return new CreditAgency(2);
    }

    @Bean(name = "CRDTAGY3")
    public CreditAgency crdtagy3() {
        return new CreditAgency(3);
    }

    @Bean(name = "CRDTAGY4")
    public CreditAgency crdtagy4() {
        return new CreditAgency(4);
    }

    @Bean(name = "CRDTAGY5")
    public CreditAgency crdtagy5() {
        return new CreditAgency(5);
    }
}
