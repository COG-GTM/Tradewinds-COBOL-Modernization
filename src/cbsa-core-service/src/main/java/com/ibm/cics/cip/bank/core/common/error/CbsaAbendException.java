package com.ibm.cics.cip.bank.core.common.error;

import org.springframework.http.HttpStatus;

/**
 * EXEC CICS ABEND ABCODE(xxxx) equivalent (e.g. HWPT = PROCTRAN write failed, HROL = rollback failed).
 * Rendered as HTTP 500 with the abend code; the surrounding @Transactional method rolls back.
 */
public class CbsaAbendException extends CbsaException {

    private final String abendCode;
    private final String program;

    public CbsaAbendException(String abendCode, String message) {
        this(abendCode, null, message);
    }

    /** @param program legacy program name (ABND-PROGRAM), e.g. "CRECUST" */
    public CbsaAbendException(String abendCode, String program, String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, message, null);
        this.abendCode = abendCode;
        this.program = program;
    }

    public String program() {
        return program;
    }

    public String abendCode() {
        return abendCode;
    }
}
