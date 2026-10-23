package com.ibm.cics.cip.bank.core.abndproc;

import com.ibm.cics.cip.bank.core.common.error.AbendEvent;
import com.ibm.cics.cip.bank.core.common.proctran.TaskNumberSupplier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Port of {@code ABNDPROC.cbl}: {@code EXEC CICS WRITE FILE('ABNDFILE') FROM(WS-ABND-AREA) RIDFLD(ABND-VSAM-KEY)}.
 * ABNDFILE is a KSDS keyed by {@code ABND-VSAM-KEY}; it is held in memory (the canonical schema has no ABNDFILE
 * table). A duplicate key is DUPREC: ABNDPROC DISPLAYs the error and RETURNs without writing.
 *
 * <p>Abends raised by the service ({@link AbendEvent}, the programs' {@code LINK PROGRAM(ABNDPROC)}) are written here
 * too, with the ABNDINFO fields the programs fill: ABSTIME / EIBTASKN key, APPLID, date, time, code, program, text.
 */
@Component
public class AbendFile {

    private record Key(long utime, int taskNo) {
    }

    private static final Comparator<Key> KEY_ORDER =
            Comparator.comparingLong(Key::utime).thenComparingInt(Key::taskNo);

    /** {@code EXEC CICS ASSIGN APPLID} of the migrated region. */
    static final String APPLID = "CBSACORE";

    /** ABSTIME epoch: milliseconds since 1900-01-01T00:00:00Z. */
    static final Instant ABSTIME_EPOCH = Instant.parse("1900-01-01T00:00:00Z");

    private static final DateTimeFormatter ABND_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter ABND_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ConcurrentSkipListMap<Key, AbendRecord> records = new ConcurrentSkipListMap<>(KEY_ORDER);
    private final TaskNumberSupplier taskNumbers;
    private final Clock clock;

    public AbendFile(TaskNumberSupplier taskNumbers, Clock clock) {
        this.taskNumbers = taskNumbers;
        this.clock = clock;
    }

    /** A service abend: the record the abending program LINKs to ABNDPROC with. */
    @EventListener
    public void abended(AbendEvent event) {
        if (event == null) {
            return;
        }
        Instant time = event.time() == null ? Instant.now(clock) : event.time();
        ZonedDateTime local = time.atZone(clock.getZone());
        write(new AbendRecord(Duration.between(ABSTIME_EPOCH, time).toMillis(), (int) taskNumbers.currentTaskNumber(),
                APPLID, "", ABND_DATE.format(local), ABND_TIME.format(local), event.abendCode(), event.program(),
                0, 0, 0, event.freeform()));
    }

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
