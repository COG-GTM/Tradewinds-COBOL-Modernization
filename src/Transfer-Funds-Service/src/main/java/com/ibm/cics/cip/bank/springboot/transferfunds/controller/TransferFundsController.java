/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.controller;

import com.ibm.cics.cip.bank.springboot.transferfunds.dto.TransferRequest;
import com.ibm.cics.cip.bank.springboot.transferfunds.dto.TransferResponse;
import com.ibm.cics.cip.bank.springboot.transferfunds.service.TransferFundsService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point for the modernized XFRFUN program.
 *
 * <p>
 * Where the original COBOL was driven by a CICS commarea via
 * {@code EXEC CICS LINK PROGRAM('XFRFUN')}, this controller exposes the same
 * capability over HTTP. A {@code POST} to {@code /xfrfun/transfer} corresponds
 * to one invocation of the program; the {@link TransferRequest} /
 * {@link TransferResponse} pair maps to the {@code DFHCOMMAREA} input/output
 * fields defined in {@code XFRFUN.cpy}.
 * </p>
 */
@RestController
@RequestMapping("/xfrfun")
public class TransferFundsController
{

	private final TransferFundsService transferFundsService;

	public TransferFundsController(TransferFundsService transferFundsService)
	{
		this.transferFundsService = transferFundsService;
	}

	@PostMapping("/transfer")
	public ResponseEntity<TransferResponse> transfer(
			@Valid @RequestBody TransferRequest request)
	{
		return ResponseEntity.ok(transferFundsService.transfer(request));
	}
}
