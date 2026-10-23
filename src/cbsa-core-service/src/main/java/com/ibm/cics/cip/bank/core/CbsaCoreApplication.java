package com.ibm.cics.cip.bank.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * CBSA core banking service: Java 21 / Spring Boot 3 replacement for the COBOL/CICS business
 * programs in src/base/cobol_src. Each business program lives in its own sub-package
 * (com.ibm.cics.cip.bank.core.&lt;pkg&gt;); shared infrastructure lives in {@code common}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CbsaCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CbsaCoreApplication.class, args);
    }
}
