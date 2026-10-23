/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.paymentinterface;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payment interface. Point it at cbsa-core-service with
 * {@code --cbsa.core.base-url=http://host:port} (or env CBSA_CORE_URL).
 */
@SpringBootApplication
public class PaymentInterface
{

	public static void main(String[] args)
	{
		SpringApplication.run(PaymentInterface.class, args);
	}
}
