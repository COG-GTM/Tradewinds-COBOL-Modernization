/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.controllers;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ibm.cics.cip.bank.springboot.transferfunds.jsonclasses.transferfunds.TransferFundsForm;
import com.ibm.cics.cip.bank.springboot.transferfunds.jsonclasses.transferfunds.TransferFundsResponse;
import com.ibm.cics.cip.bank.springboot.transferfunds.service.SameAccountTransferException;
import com.ibm.cics.cip.bank.springboot.transferfunds.service.TransferFundsService;
import com.ibm.cics.cip.bank.springboot.transferfunds.service.TransferRollbackException;

import jakarta.validation.Valid;

/**
 * REST controller exposing the Transfer Funds capability.
 *
 * Follows the existing pattern in the Payment Interface's WebController:
 * receives a form/JSON request, delegates to the service layer, and returns
 * a response mirroring the COBOL COMMAREA output fields.
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferFundsController
{


	private static final Logger log = LoggerFactory
			.getLogger(TransferFundsController.class);

	private final TransferFundsService transferFundsService;


	public TransferFundsController(TransferFundsService transferFundsService)
	{
		this.transferFundsService = transferFundsService;
	}


	/**
	 * Execute a funds transfer between two accounts.
	 *
	 * Maps to the COBOL XFRFUN program invoked via BNK1TFN (BMS transaction).
	 */
	@PostMapping
	public ResponseEntity<TransferFundsResponse> transfer(
			@Valid @RequestBody TransferFundsForm form)
	{
		log.info("Transfer request received: {}", form);

		TransferFundsResponse response = transferFundsService
				.transferFunds(form);

		if ("Y".equals(response.getSuccess()))
		{
			return ResponseEntity.ok(response);
		}
		else
		{
			return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
					.body(response);
		}
	}


	@ExceptionHandler(SameAccountTransferException.class)
	public ResponseEntity<Map<String, String>> handleSameAccount(
			SameAccountTransferException ex)
	{
		log.error("Same-account transfer rejected: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", ex.getMessage(), "failCode", "SAME"));
	}


	@ExceptionHandler(TransferRollbackException.class)
	public ResponseEntity<Map<String, String>> handleRollback(
			TransferRollbackException ex)
	{
		log.error("Transfer rolled back: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
				Map.of("error", ex.getMessage(), "failCode", "ROLLBACK"));
	}
}
