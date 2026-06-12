/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.controller;

import java.util.HashMap;
import java.util.Map;

import com.ibm.cics.cip.bank.springboot.transferfunds.exception.SameAccountTransferException;
import com.ibm.cics.cip.bank.springboot.transferfunds.exception.TransferProcessingException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates the business exceptions raised by the transfer service into HTTP
 * responses.
 *
 * <p>
 * The original program reacted to these conditions by issuing
 * {@code EXEC CICS ABEND}; here they become structured error responses instead
 * of abending the transaction.
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler
{

	@ExceptionHandler(SameAccountTransferException.class)
	public ResponseEntity<Map<String, Object>> handleSameAccount(
			SameAccountTransferException e)
	{
		// COBOL abend code 'SAME'.
		return error(HttpStatus.UNPROCESSABLE_ENTITY,
				SameAccountTransferException.ABEND_CODE, e.getMessage());
	}

	@ExceptionHandler(TransferProcessingException.class)
	public ResponseEntity<Map<String, Object>> handleProcessing(
			TransferProcessingException e)
	{
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR", e.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleValidation(
			MethodArgumentNotValidException e)
	{
		StringBuilder details = new StringBuilder();
		for (FieldError fieldError : e.getBindingResult().getFieldErrors())
		{
			if (details.length() > 0)
			{
				details.append("; ");
			}
			details.append(fieldError.getField()).append(": ")
					.append(fieldError.getDefaultMessage());
		}
		return error(HttpStatus.BAD_REQUEST, "VALIDATION", details.toString());
	}

	private ResponseEntity<Map<String, Object>> error(HttpStatus status,
			String code, String message)
	{
		Map<String, Object> body = new HashMap<>();
		body.put("status", status.value());
		body.put("code", code);
		body.put("message", message);
		return ResponseEntity.status(status).body(body);
	}
}
