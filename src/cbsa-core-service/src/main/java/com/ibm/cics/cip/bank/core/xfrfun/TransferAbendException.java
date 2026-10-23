package com.ibm.cics.cip.bank.core.xfrfun;

import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import org.springframework.http.HttpStatus;

/**
 * XFRFUN {@code EXEC CICS ABEND ABCODE(x)} preceded by {@code LINK PROGRAM(ABNDPROC)}, for the abends whose
 * HTTP mapping is not the plain 500 of {@code CbsaAbendException}: {@code SAME} (400, no fail code) and the
 * {@code FROM} / {@code TO  } update failures (500, fail code 3). {@link TransferController} renders it with
 * both {@code failCode} and {@code abendCode} and publishes the {@code AbendEvent}.
 */
public class TransferAbendException extends CbsaException {

    private final String abendCode;

    public TransferAbendException(HttpStatus status, String abendCode, String failCode, String message) {
        super(status, message, failCode);
        this.abendCode = abendCode;
    }

    public String abendCode() {
        return abendCode;
    }

    public String program() {
        return TransferService.PROGRAM;
    }
}
