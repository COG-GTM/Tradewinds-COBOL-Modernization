package com.ibm.cics.cip.bank.core.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Error body for every non-2xx response: {@code {"errorMessage": "...", "failCode": "1", "abendCode": "HWPT"}}.
 * {@code errorMessage} matches the legacy Liberty JAX-RS resources (the React UI alerts it); the
 * other fields are omitted when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String errorMessage, String failCode, String abendCode) {

    public static ErrorResponse of(String message) {
        return new ErrorResponse(message, null, null);
    }
}
