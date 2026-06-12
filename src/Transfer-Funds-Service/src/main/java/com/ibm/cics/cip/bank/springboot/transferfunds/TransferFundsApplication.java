/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot entry point for the modernized XFRFUN (Transfer Funds) service.
 */
@SpringBootApplication
public class TransferFundsApplication
{

	public static void main(String[] args)
	{
		SpringApplication.run(TransferFundsApplication.class, args);
	}

	/**
	 * System clock used for PROCTRAN date/time stamping. Declared as a bean so
	 * tests can substitute a fixed clock, mirroring CICS {@code ASKTIME}.
	 */
	@Bean
	public Clock clock()
	{
		return Clock.systemDefaultZone();
	}
}
