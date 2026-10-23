package com.ibm.cics.cip.bank.core.abndproc;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ibm.cics.cip.bank.core.common.error.AbendEvent;

/** {@code GET /banking/abend?limit=}: the ABNDFILE records captured by {@link AbendRecorder}. */
@RestController
@RequestMapping("/banking/abend")
public class AbendController {

    static final int DEFAULT_LIMIT = 100;

    private final AbendRecorder recorder;

    public AbendController(AbendRecorder recorder) {
        this.recorder = recorder;
    }

    @GetMapping
    public List<AbendEvent> recent(@RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return recorder.recent(limit);
    }
}
