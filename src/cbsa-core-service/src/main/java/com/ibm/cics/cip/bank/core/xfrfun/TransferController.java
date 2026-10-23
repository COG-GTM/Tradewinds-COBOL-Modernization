package com.ibm.cics.cip.bank.core.xfrfun;

import com.ibm.cics.cip.bank.core.common.error.AbendEvent;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import com.ibm.cics.cip.bank.core.common.error.ErrorResponse;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST face of XFRFUN (BNK1TFN / z/OS Connect transfer): {@code PUT /banking/account/transfer/{id}}. */
@RestController
@RequestMapping("/banking/account")
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);

    private final TransferService service;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public TransferController(TransferService service, ApplicationEventPublisher events, Clock clock) {
        this.service = service;
        this.events = events;
        this.clock = clock;
    }

    /** {@code id} = COMM-FACCNO; body {@code targetAccount} = COMM-TACCNO, {@code amount} = COMM-AMT. */
    @PutMapping("/transfer/{id}")
    public TransferResponse transfer(@PathVariable("id") long id, @RequestBody TransferRequest request) {
        if (request == null || request.targetAccount() == null) {
            throw CbsaException.badRequest("targetAccount is required");
        }
        return TransferResponse.of(service.transfer(id, request.targetAccount(), request.amount()));
    }

    /** {@code LINK PROGRAM(ABNDPROC)} + {@code EXEC CICS ABEND}: publish the abend record, then render it. */
    @ExceptionHandler(TransferAbendException.class)
    public ResponseEntity<ErrorResponse> abend(TransferAbendException e) {
        log.error("ABEND {}: {}", e.abendCode(), e.getMessage());
        events.publishEvent(new AbendEvent(Instant.now(clock), e.abendCode(), e.program(), e.getMessage()));
        return ResponseEntity.status(e.status())
                .body(new ErrorResponse(e.getMessage(), e.failCode(), e.abendCode()));
    }
}
