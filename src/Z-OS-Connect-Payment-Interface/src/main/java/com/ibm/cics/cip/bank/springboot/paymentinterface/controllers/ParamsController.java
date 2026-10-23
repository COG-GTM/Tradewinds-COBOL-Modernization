/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.paymentinterface.controllers;

import com.ibm.cics.cip.bank.springboot.paymentinterface.CbsaCoreClient;
import com.ibm.cics.cip.bank.springboot.paymentinterface.jsonclasses.paymentinterface.PaymentInterfaceJson;
import com.ibm.cics.cip.bank.springboot.paymentinterface.jsonclasses.paymentinterface.TransferForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ParamsController
{

	private static final Logger log = LoggerFactory
			.getLogger(ParamsController.class);

	private final CbsaCoreClient cbsaCoreClient;


	public ParamsController(CbsaCoreClient cbsaCoreClient)
	{
		this.cbsaCoreClient = cbsaCoreClient;
	}


	// This follows a very similar format to the form submitting equivalents in
	// WebController.java
	// Instead of a form object, parameters required in the url.
	// Returns the response COMMAREA as-is, or an empty body on any error (as
	// the z/OS Connect version did).
	@PostMapping("/submit")
	public PaymentInterfaceJson submit(
			@RequestParam(name = "acctnum", required = true) String acctNumber,
			@RequestParam(name = "amount", required = true) float amount,
			@RequestParam(name = "organisation", required = true) String organisation)
	{
		log.info("AcctNumber: {}, Amount {}, Organisation {}", acctNumber,
				amount, organisation);
		TransferForm transferForm = new TransferForm(acctNumber, amount,
				organisation);
		PaymentInterfaceJson transferJson = new PaymentInterfaceJson(
				transferForm);
		try
		{
			PaymentInterfaceJson responseObj = cbsaCoreClient
					.makePayment(transferJson);
			log.info("{}", responseObj);
			return responseObj;
		}
		catch (Exception e)
		{
			log.info(e.toString());
		}
		return null;
	}
}
