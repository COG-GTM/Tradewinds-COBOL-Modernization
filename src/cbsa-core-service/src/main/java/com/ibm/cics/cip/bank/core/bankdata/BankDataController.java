package com.ibm.cics.cip.bank.core.bankdata;

import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only BANKDATA endpoints, registered only when {@code cbsa.bankdata.endpoint.enabled=true}.
 * <ul>
 * <li>{@code POST /banking/admin/bankdata} - run BANKDATA against the database (replaces the sort code's
 *     CUSTOMER / ACCOUNT / CONTROL rows), returns row counts.</li>
 * <li>{@code POST /banking/admin/bankdata/sql} - return the generated rows as seed.sql text, no DB change.</li>
 * </ul>
 * Body: {@code {"startKey":1,"endKey":30,"stepKey":1,"seed":42}} (BANKDATA PARM).
 *
 * <p>Every request must carry header {@value #TOKEN_HEADER} equal to {@code cbsa.bankdata.endpoint.token};
 * when that property is blank all requests are rejected (403). A run may generate at most
 * {@code cbsa.bankdata.endpoint.max-customers} customers (default {@value #DEFAULT_MAX_CUSTOMERS}).
 */
@RestController
@RequestMapping("/banking/admin/bankdata")
@ConditionalOnProperty(name = "cbsa.bankdata.endpoint.enabled", havingValue = "true")
public class BankDataController {

    static final String TOKEN_HEADER = "X-CBSA-Admin-Token";
    static final long DEFAULT_MAX_CUSTOMERS = 10_000;

    /** BANKDATA PARM {@code start,end,step,seed}. */
    public record BankDataParm(long startKey, long endKey, long stepKey, long seed) {
    }

    private final BankDataGenerator generator;
    private final BankDataLoader loader;
    private final BankDataSqlWriter sqlWriter;
    private final byte[] token;
    private final long maxCustomers;

    public BankDataController(BankDataGenerator generator, BankDataLoader loader, BankDataSqlWriter sqlWriter,
            @Value("${cbsa.bankdata.endpoint.token:}") String token,
            @Value("${cbsa.bankdata.endpoint.max-customers:" + DEFAULT_MAX_CUSTOMERS + "}") long maxCustomers) {
        this.generator = generator;
        this.loader = loader;
        this.sqlWriter = sqlWriter;
        this.token = token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
        this.maxCustomers = maxCustomers;
    }

    @PostMapping
    public Map<String, Object> run(@RequestHeader(name = TOKEN_HEADER, required = false) String suppliedToken,
            @RequestBody BankDataParm parm) {
        GeneratedBank bank = generate(suppliedToken, parm);
        loader.load(bank);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sortCode", bank.sortCode());
        result.put("customers", bank.customers().size());
        result.put("accounts", bank.accounts().size());
        bank.controls().forEach(c -> result.put(c.name(), c.valueNum()));
        return result;
    }

    @PostMapping(path = "/sql", produces = MediaType.TEXT_PLAIN_VALUE)
    public String sql(@RequestHeader(name = TOKEN_HEADER, required = false) String suppliedToken,
            @RequestBody BankDataParm parm) {
        return sqlWriter.toSql(generate(suppliedToken, parm));
    }

    private GeneratedBank generate(String suppliedToken, BankDataParm parm) {
        authorise(suppliedToken);
        if (parm.stepKey() > 0 && parm.endKey() >= parm.startKey()
                && (parm.endKey() - parm.startKey()) / parm.stepKey() + 1 > maxCustomers) {
            throw new CbsaException(HttpStatus.BAD_REQUEST,
                    "At most " + maxCustomers + " customers can be generated per request", "12");
        }
        return generator.generate(parm.startKey(), parm.endKey(), parm.stepKey(), parm.seed());
    }

    private void authorise(String suppliedToken) {
        if (token == null || suppliedToken == null
                || !MessageDigest.isEqual(token, suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new CbsaException(HttpStatus.FORBIDDEN, "Not authorised", null);
        }
    }
}
