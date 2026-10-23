package com.ibm.cics.cip.bank.core.abndproc;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import org.springframework.stereotype.Component;

/**
 * Port of {@code ABNDPROC.cbl}: {@code EXEC CICS WRITE FILE('ABNDFILE') FROM(WS-ABND-AREA) RIDFLD(ABND-VSAM-KEY)}.
 * ABNDFILE is a KSDS keyed by {@code ABND-VSAM-KEY}; it is held in memory (the canonical schema has no ABNDFILE
 * table). A duplicate key is DUPREC: ABNDPROC DISPLAYs the error and RETURNs without writing.
 */
@Component
public class AbendFile {

    private record Key(long utime, int taskNo) {
    }

    private static final Comparator<Key> KEY_ORDER =
            Comparator.comparingLong(Key::utime).thenComparingInt(Key::taskNo);

    private final ConcurrentSkipListMap<Key, AbendRecord> records = new ConcurrentSkipListMap<>(KEY_ORDER);

    /** ABNDPROC: returns the record as written, or {@code null} on DUPREC (nothing written). */
    public AbendRecord write(AbendRecord commarea) {
        AbendRecord record = commarea.toCopybook();
        AbendRecord previous = records.putIfAbsent(new Key(record.utimeKey(), record.taskNoKey()), record);
        return previous == null ? record : null;
    }

    /** All records in key order. */
    public List<AbendRecord> records() {
        return List.copyOf(records.values());
    }

    /** Empties the file (DELETE of every record). */
    public void clear() {
        records.clear();
    }
}
