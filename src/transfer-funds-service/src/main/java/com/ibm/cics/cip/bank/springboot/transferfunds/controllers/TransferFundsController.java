/*
 *
 *    Copyright IBM Corp. 2023
 *
 */
package com.ibm.cics.cip.bank.springboot.transferfunds.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ibm.cics.cip.bank.springboot.transferfunds.dto.TransferRequest;
import com.ibm.cics.cip.bank.springboot.transferfunds.dto.TransferResponse;
import com.ibm.cics.cip.bank.springboot.transferfunds.service.TransferFundsService;

import jakarta.validation.Valid;

/**
 * REST facade for the transfer-funds capability. Replaces the CICS
 * COMMAREA invocation of XFRFUN (LINK from BNK1TFN or z/OS Connect).
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferFundsController
{
	private static final Logger LOG = LoggerFactory
			.getLogger(TransferFundsController.class);

	private final TransferFundsService transferFundsService;

	public TransferFundsController(TransferFundsService transferFundsService)
	{
		this.transferFundsService = transferFundsService;
	}

	@PostMapping
	public ResponseEntity<TransferResponse> transfer(
			@Valid @RequestBody TransferRequest request)
	{
		TransferResponse response = transferFundsService.transfer(request);
		HttpStatus status = response.isSuccess() ? HttpStatus.OK
				: HttpStatus.UNPROCESSABLE_ENTITY;
		return ResponseEntity.status(status).body(response);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<String> handleValidation(
			MethodArgumentNotValidException e)
	{
		LOG.warn("Request validation failed: {}", e.getMessage());
		return ResponseEntity.badRequest().body("Invalid request");
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<String> handleError(Exception e)
	{
		LOG.error("Transfer failed", e);
		return ResponseEntity.internalServerError().body("An error occurred");
	}
}
