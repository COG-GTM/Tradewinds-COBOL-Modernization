import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cics.cip.bank.core.CbsaCoreApplication;
import com.ibm.cics.cip.bank.core.common.proctran.TaskNumberSupplier;
import com.ibm.cics.cip.bank.core.common.seed.SeedDataLoader;
import com.ibm.cics.cip.bank.core.screens.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Drives the migrated 3270 screen layer (com.ibm.cics.cip.bank.core.screens, no REST surface) with the
 * terminal-mode inputs of the baseline transcripts. One Spring context (H2 in-memory, fixed clock / seed);
 * before every case the four tables are emptied, the canonical seed reloaded and EIBTASKN reset to
 * env.CBSA_TASKN, like the legacy harness's fresh SQLite copy. Writes one JSON object per case:
 * {file, events, before, after, error}.
 * Usage: ScreenParity OUT.jsonl transcript.json...
 */
public class ScreenParity {
    public static class ResettableTaskNumbers extends TaskNumberSupplier {
        final AtomicLong next = new AtomicLong(1);
        @Override public long nextTaskNumber() { return next.getAndIncrement(); }
    }

    static final String[] TABLES = {"CUSTOMER", "ACCOUNT", "PROCTRAN", "CONTROL"};

    public static void main(String[] args) throws Exception {
        SpringApplication app = new SpringApplication(CbsaCoreApplication.class);
        app.addInitializers(ctx -> ((GenericApplicationContext) ctx).registerBean("parityTaskNumbers",
                ResettableTaskNumbers.class, ResettableTaskNumbers::new, bd -> bd.setPrimary(true)));
        ObjectMapper json = new ObjectMapper();
        try (ConfigurableApplicationContext ctx = app.run("--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:parityscreens;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--cbsa.clock.fixed=2026-01-15T10:30:00Z", "--cbsa.random.seed=42",
                "--cbsa.seed.location=file:../../baseline/seed/seed.sql", "--logging.level.root=WARN")) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            SeedDataLoader seed = ctx.getBean(SeedDataLoader.class);
            ResettableTaskNumbers tasks = ctx.getBean(ResettableTaskNumbers.class);
            List<String> lines = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                JsonNode t = json.readTree(Path.of(args[i]).toFile());
                for (String table : TABLES) jdbc.update("DELETE FROM " + table);
                seed.afterPropertiesSet();
                tasks.next.set(t.path("env").path("CBSA_TASKN").asLong(100));
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("file", args[i]);
                rec.put("before", dump(jdbc));
                try {
                    String prog = t.get("program").asText();
                    ScreenInput in = input(t.get("commarea_in"));
                    ScreenOutput out = prog.equals("BNKMENU") ? ctx.getBean(MenuRouter.class).handle(in)
                            : ((ScreenHandler) ctx.getBean(handler(prog))).handle(in);
                    rec.put("events", out.events());
                } catch (Exception e) {
                    rec.put("error", e.toString());
                }
                rec.put("after", dump(jdbc));
                lines.add(json.writeValueAsString(rec));
            }
            Files.write(Path.of(args[0]), lines);
        }
    }

    static Class<?> handler(String program) {
        return switch (program) {
            case "BNKMENU" -> MenuRouterHandler.class;
            case "BNK1DCS" -> DisplayCustomerScreen.class;
            case "BNK1CCS" -> CreateCustomerScreen.class;
            case "BNK1CAC" -> CreateAccountScreen.class;
            case "BNK1UAC" -> UpdateAccountScreen.class;
            case "BNK1DAC" -> DisplayAccountScreen.class;
            case "BNK1CRA" -> CreditDebitScreen.class;
            case "BNK1TFN" -> TransferScreen.class;
            case "BNK1CCA" -> ListAccountsScreen.class;
            default -> throw new IllegalArgumentException(program);
        };
    }

    /** Placeholder resolved in main for BNKMENU (MenuRouter is not a ScreenHandler). */
    interface MenuRouterHandler {}

    static ScreenInput input(JsonNode in) {
        Map<String, String> fields = new LinkedHashMap<>();
        JsonNode mf = in.get("map_fields");
        if (mf != null && mf.isObject()) mf.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue().asText()));
        JsonNode ca = in.get("commarea");
        Map<String, String> commarea = ca == null || ca.isNull() ? null : Map.of();
        String aid = in.hasNonNull("aid") ? in.get("aid").asText() : "ENTER";
        return new ScreenInput(AidKey.parse(aid), fields, commarea);
    }

    static Map<String, List<Map<String, Object>>> dump(JdbcTemplate jdbc) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (String t : TABLES) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM " + t)) {
                Map<String, Object> row = new LinkedHashMap<>();
                r.forEach((k, v) -> row.put(k, v == null ? null : v instanceof Number n ? (Object) Double.valueOf(n.toString())
                        : v.toString().stripTrailing()));
                rows.add(row);
            }
            out.put(t, rows);
        }
        return out;
    }
}
