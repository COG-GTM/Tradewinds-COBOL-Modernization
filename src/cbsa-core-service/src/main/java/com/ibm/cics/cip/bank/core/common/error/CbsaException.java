package com.ibm.cics.cip.bank.core.common.error;

import org.springframework.http.HttpStatus;

/**
 * Business failure carrying the HTTP status, the user-facing message and (optionally) the legacy
 * COMMAREA fail code (e.g. CRECUST 'T', DBCRFUN '3'). Rendered by {@link GlobalExceptionHandler}.
 */
public class CbsaException extends RuntimeException {

    private final HttpStatus status;
    private final String failCode;

    public CbsaException(HttpStatus status, String message, String failCode) {
        super(message);
        this.status = status;
        this.failCode = failCode;
    }

    public static CbsaException badRequest(String message) {
        return new CbsaException(HttpStatus.BAD_REQUEST, message, null);
    }

    public static CbsaException badRequest(String message, String failCode) {
        return new CbsaException(HttpStatus.BAD_REQUEST, message, failCode);
    }

    public static CbsaException notFound(String message) {
        return new CbsaException(HttpStatus.NOT_FOUND, message, null);
    }

    public static CbsaException notFound(String message, String failCode) {
        return new CbsaException(HttpStatus.NOT_FOUND, message, failCode);
    }

    public static CbsaException conflict(String message, String failCode) {
        return new CbsaException(HttpStatus.CONFLICT, message, failCode);
    }

    public static CbsaException serverError(String message, String failCode) {
        return new CbsaException(HttpStatus.INTERNAL_SERVER_ERROR, message, failCode);
    }

    public HttpStatus status() {
        return status;
    }

    public String failCode() {
        return failCode;
    }
}
