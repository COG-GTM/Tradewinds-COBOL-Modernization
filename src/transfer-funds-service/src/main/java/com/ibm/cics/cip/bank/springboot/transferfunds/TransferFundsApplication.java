/*
 *
 *    Copyright IBM Corp. 2023
 *
 */
package com.ibm.cics.cip.bank.springboot.transferfunds;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Spring Boot entry point for the transfer-funds service, the Java
 * modernization of the CBSA XFRFUN COBOL/CICS program.
 */
@SpringBootApplication
@EnableRetry
public class TransferFundsApplication
{
	public static void main(String[] args)
	{
		SpringApplication.run(TransferFundsApplication.class, args);
	}
}
