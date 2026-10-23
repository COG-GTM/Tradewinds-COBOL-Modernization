package com.ibm.cics.cip.bank.core.bankdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Byte-for-byte parity with the legacy GnuCOBOL BANKDATA itself (no seed activity): runs legacy BANKDATA in
 * batch mode with PARM '1,30,1,42' exactly as legacy/make-seed.sh does and compares make_seed.py's dump with
 * {@link BankDataSqlWriter#toSql}. Skipped when the harness is not built ({@code legacy/ensure-build.sh}) or
 * sqlite3 / python3 are missing.
 */
class BankDataLegacyHarnessParityTest {

    static final Path LEGACY = Path.of("../../legacy").toAbsolutePath().normalize();

    @TempDir
    Path work;

    @Test
    void javaOutputEqualsLegacyBankdataRun() throws Exception {
        Path cbsarun = LEGACY.resolve("build/cbsarun");
        assumeTrue(Files.isExecutable(cbsarun), "legacy harness not built");
        assumeTrue(onPath("sqlite3") && onPath("python3"), "sqlite3/python3 not available");

        Path db = work.resolve("seed.db");
        Path schema = LEGACY.resolve("../baseline/seed/schema-legacy-harness.sql").normalize();
        run(List.of("sqlite3", db.toString()), schema.toFile());
        ProcessBuilder bankdata = new ProcessBuilder(cbsarun.toString(), db.toString(), "batch", "BANKDATA",
                "1,30,1,42", "-", work.resolve("events.txt").toString());
        bankdata.environment().put("CBSA_FIXED_TIME", "2026-01-15T10:30:00Z");
        bankdata.environment().put("CBSA_RANDOM_SEED", "42");
        bankdata.environment().put("DD_VSAM", work.resolve("customer.dat").toString());
        bankdata.environment().put("CBSA_LIB", LEGACY.resolve("build/lib").toString());
        exec(bankdata, null);
        Path makeSeed = LEGACY.resolve("tools/make_seed.py");
        run(List.of("python3", makeSeed.toString(), "load-customers", db.toString(),
                work.resolve("customer.dat").toString()), null);
        Path raw = work.resolve("raw.sql");
        run(List.of("python3", makeSeed.toString(), "dump", db.toString(), raw.toString()), null);

        String java = new BankDataSqlWriter()
                .toSql(new BankDataGenerator("987654", BankDataGeneratorTest.FIXED, 42L).generate(1, 30, 1, 42));
        assertThat(java).isEqualTo(Files.readString(raw, StandardCharsets.UTF_8));
    }

    private void run(List<String> command, File stdin) throws Exception {
        exec(new ProcessBuilder(command), stdin);
    }

    private void exec(ProcessBuilder pb, File stdin) throws Exception {
        pb.directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("out.log").toFile());
        if (stdin != null) {
            pb.redirectInput(stdin);
        }
        Process p = pb.start();
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(String.join(" ", pb.command()) + ": "
                + Files.readString(work.resolve("out.log"))).isZero();
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, exe))) {
                return true;
            }
        }
        return false;
    }
}
