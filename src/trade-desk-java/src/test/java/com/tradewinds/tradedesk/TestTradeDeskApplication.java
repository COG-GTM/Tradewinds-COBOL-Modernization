package com.tradewinds.tradedesk;

import org.springframework.boot.SpringApplication;

/** Runs the service on the test classpath (H2): {@code mvn spring-boot:test-run -Dspring-boot.run.profiles=local}. */
public class TestTradeDeskApplication {

    public static void main(String[] args) {
        SpringApplication.from(TradeDeskApplication::main).run(args);
    }
}
