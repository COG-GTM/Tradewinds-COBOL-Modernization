package com.tradewinds.tradedesk.dbcrfun.controller;

import com.tradewinds.tradedesk.dbcrfun.dto.DbcrfunRequest;
import com.tradewinds.tradedesk.dbcrfun.dto.DbcrfunResponse;
import com.tradewinds.tradedesk.dbcrfun.service.DbcrfunService;
import java.math.BigDecimal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/dbcrfun")
public class DbcrfunController {

    /** COMM-AMT PIC S9(10)V99. */
    private static final int AMOUNT_INTEGER_DIGITS = 10;
    private static final int AMOUNT_SCALE = 2;

    private final DbcrfunService service;

    public DbcrfunController(DbcrfunService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DbcrfunResponse> dbcrfun(@RequestBody DbcrfunRequest request) {
        if (request.accno() == null || request.amt() == null || request.origin() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accno, amt and origin are required");
        }
        if (!fitsCommAmt(request.amt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amt must fit S9(10)V99");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.process(request));
    }

    private static boolean fitsCommAmt(BigDecimal amt) {
        BigDecimal normalized = amt.stripTrailingZeros();
        return Math.max(normalized.scale(), 0) <= AMOUNT_SCALE
                && normalized.precision() - normalized.scale() <= AMOUNT_INTEGER_DIGITS;
    }
}
