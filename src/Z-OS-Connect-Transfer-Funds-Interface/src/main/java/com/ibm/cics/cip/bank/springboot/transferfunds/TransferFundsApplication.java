/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TransferFundsApplication
{


	public static void main(String[] args)
	{
		final Logger log = LoggerFactory
				.getLogger(TransferFundsApplication.class);
		log.info("Starting Transfer Funds Service (modernised XFRFUN)");
		SpringApplication.run(TransferFundsApplication.class, args);
	}
}
