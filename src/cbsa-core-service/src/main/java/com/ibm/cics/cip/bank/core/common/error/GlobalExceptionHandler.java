package com.ibm.cics.cip.bank.core.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions to {@link ErrorResponse} with the status codes of docs/migration/api-contract.md. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ApplicationEventPublisher events;
    private final Clock clock;

    public GlobalExceptionHandler(ApplicationEventPublisher events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @ExceptionHandler(CbsaAbendException.class)
    public ResponseEntity<ErrorResponse> abend(CbsaAbendException e) {
        log.error("ABEND {}: {}", e.abendCode(), e.getMessage());
        events.publishEvent(new AbendEvent(Instant.now(clock), e.abendCode(), e.program(), e.getMessage()));
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.getMessage(), e.failCode(), e.abendCode()));
    }

    @ExceptionHandler(CbsaException.class)
    public ResponseEntity<ErrorResponse> business(CbsaException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.getMessage(), e.failCode(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe != null ? fe.getField() + " " + fe.getDefaultMessage() : "Invalid request";
        return badRequest(msg);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> invalidParams(HandlerMethodValidationException e) {
        return badRequest("Invalid request parameters");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> malformed(Exception e) {
        return badRequest(e instanceof HttpMessageNotReadableException ? "Malformed JSON request" : e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> noResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("Not found: /" + e.getResourcePath()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of("Internal server error"));
    }

    private static ResponseEntity<ErrorResponse> badRequest(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(msg));
    }
}
