/*
 *
 *    Copyright IBM Corp. 2023
 *
 *
 */
package com.ibm.cics.cip.bank.springboot.customerservices;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Customer services interface. Point it at cbsa-core-service with
 * {@code --cbsa.core.base-url=http://host:port} (or env CBSA_CORE_URL).
 */
@SpringBootApplication
public class CustomerServices
{

	public static void main(String[] args)
	{
		SpringApplication.run(CustomerServices.class, args);
	}
}
