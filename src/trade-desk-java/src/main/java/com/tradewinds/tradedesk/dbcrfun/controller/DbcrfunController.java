package com.tradewinds.tradedesk.dbcrfun.controller;

import com.tradewinds.tradedesk.dbcrfun.dto.DbcrfunRequest;
import com.tradewinds.tradedesk.dbcrfun.dto.DbcrfunResponse;
import com.tradewinds.tradedesk.dbcrfun.service.DbcrfunService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/dbcrfun")
public class DbcrfunController {

    private final DbcrfunService service;

    public DbcrfunController(DbcrfunService service) {
        this.service = service;
    }

    @PostMapping
    public DbcrfunResponse dbcrfun(@RequestBody DbcrfunRequest request) {
        if (request.accno() == null || request.amt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accno and amt are required");
        }
        return service.process(request);
    }
}
