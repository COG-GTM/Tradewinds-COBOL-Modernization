package com.tradewinds.tradedesk.dbcrfun;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter for DBCRFUN: the JSON body replaces the PAYDBCR COMMAREA.
 * Business failures are returned with HTTP 200 and success='N', as in the COMMAREA.
 */
@RestController
@RequestMapping("/api/dbcrfun")
public class DbcrfunController {

    private final DbcrfunService service;

    public DbcrfunController(DbcrfunService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DbcrfunResponse> applyTransaction(@Valid @RequestBody DbcrfunRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.applyTransaction(request));
    }
}
