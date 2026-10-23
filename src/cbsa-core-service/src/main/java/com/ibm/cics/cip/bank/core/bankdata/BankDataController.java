package com.ibm.cics.cip.bank.core.bankdata;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 */
@RestController
@RequestMapping("/banking/admin/bankdata")
@ConditionalOnProperty(name = "cbsa.bankdata.endpoint.enabled", havingValue = "true")
public class BankDataController {

    /** BANKDATA PARM {@code start,end,step,seed}. */
    public record BankDataParm(long startKey, long endKey, long stepKey, long seed) {
    }

    private final BankDataGenerator generator;
    private final BankDataLoader loader;
    private final BankDataSqlWriter sqlWriter;

    public BankDataController(BankDataGenerator generator, BankDataLoader loader, BankDataSqlWriter sqlWriter) {
        this.generator = generator;
        this.loader = loader;
        this.sqlWriter = sqlWriter;
    }

    @PostMapping
    public Map<String, Object> run(@RequestBody BankDataParm parm) {
        GeneratedBank bank = generate(parm);
        loader.load(bank);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sortCode", bank.sortCode());
        result.put("customers", bank.customers().size());
        result.put("accounts", bank.accounts().size());
        bank.controls().forEach(c -> result.put(c.name(), c.valueNum()));
        return result;
    }

    @PostMapping(path = "/sql", produces = MediaType.TEXT_PLAIN_VALUE)
    public String sql(@RequestBody BankDataParm parm) {
        return sqlWriter.toSql(generate(parm));
    }

    private GeneratedBank generate(BankDataParm parm) {
        return generator.generate(parm.startKey(), parm.endKey(), parm.stepKey(), parm.seed());
    }
}
