package com.ibm.cics.cip.bank.core.abndproc;

import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin ABNDPROC endpoints, registered only when {@code cbsa.abndproc.endpoint.enabled=true} (default false).
 * <ul>
 * <li>{@code POST /banking/admin/abend} - LINK ABNDPROC with an ABNDINFO COMMAREA ({@link AbendRecord}): {@code 201}
 *     with the record as written to ABNDFILE, {@code 409} on a duplicate {@code ABND-VSAM-KEY} (DUPREC).</li>
 * <li>{@code GET /banking/admin/abend} - every ABNDFILE record in key order.</li>
 * </ul>
 * Every request must carry header {@value #TOKEN_HEADER} equal to {@code cbsa.abndproc.endpoint.token}; when that
 * property is blank all requests are rejected (403).
 */
@RestController
@RequestMapping("/banking/admin/abend")
@ConditionalOnProperty(name = "cbsa.abndproc.endpoint.enabled", havingValue = "true")
public class AbendRecordController {

    static final String TOKEN_HEADER = "X-CBSA-Admin-Token";

    private final AbendFile file;
    private final byte[] token;

    public AbendRecordController(AbendFile file, @Value("${cbsa.abndproc.endpoint.token:}") String token) {
        this.file = file;
        this.token = token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ResponseEntity<AbendRecord> write(@RequestHeader(name = TOKEN_HEADER, required = false) String supplied,
            @RequestBody AbendRecord commarea) {
        authorise(supplied);
        AbendRecord written = file.write(commarea);
        if (written == null) {
            throw CbsaException.conflict("ABNDFILE record " + commarea.utimeKey() + "/"
                    + commarea.taskNoKey() + " already exists (DUPREC).", null);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(written);
    }

    @GetMapping
    public List<AbendRecord> records(@RequestHeader(name = TOKEN_HEADER, required = false) String supplied) {
        authorise(supplied);
        return file.records();
    }

    private void authorise(String supplied) {
        if (token == null || supplied == null
                || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new CbsaException(HttpStatus.FORBIDDEN, "Admin token missing or invalid.", null);
        }
    }
}
