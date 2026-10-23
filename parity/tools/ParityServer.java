import com.ibm.cics.cip.bank.core.CbsaCoreApplication;
import com.ibm.cics.cip.bank.core.common.seed.SeedDataLoader;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * Starts the unmodified migrated application (target/classes, same beans and properties as the jar) with two
 * parity-only test seams, mirroring what the legacy harness does per transcript:
 *  - EIBTASKN: TaskNumberSupplier replaced by a resettable subclass (legacy env CBSA_TASKN)
 *  - /webui-1.0/__parity/{reset,exec,dump}: empty the four tables + reload baseline/seed/seed.sql (legacy: fresh
 *    SQLite copy), run setup SQL, and dump CUSTOMER/ACCOUNT/PROCTRAN/CONTROL as JSON.
 * No business code is replaced.
 */
public class ParityServer {
    @RestController
    @RequestMapping("/__parity")
    public static class ParityControl {
        static final String[] TABLES = {"CUSTOMER", "ACCOUNT", "PROCTRAN", "CONTROL"};
        final JdbcTemplate jdbc;
        final SeedDataLoader seed;
        final ScreenParity.ResettableTaskNumbers tasks;

        public ParityControl(JdbcTemplate jdbc, SeedDataLoader seed, ScreenParity.ResettableTaskNumbers tasks) {
            this.jdbc = jdbc;
            this.seed = seed;
            this.tasks = tasks;
        }

        @PostMapping("/reset")
        public void reset(@RequestParam("taskn") long taskn) {
            for (String t : TABLES) jdbc.update("DELETE FROM " + t);
            seed.afterPropertiesSet();
            tasks.next.set(taskn);
        }

        @PostMapping("/taskn")
        public void taskn(@RequestParam("taskn") long taskn) {
            tasks.next.set(taskn);
        }

        @PostMapping(value = "/exec", consumes = "text/plain")
        public void exec(@RequestBody String sql) {
            for (String s : sql.split(";\\s*(\\n|$)")) if (!s.isBlank()) jdbc.execute(s);
        }

        @GetMapping("/dump")
        public Map<String, List<Map<String, Object>>> dump() {
            return ScreenParity.dump(jdbc);
        }
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(CbsaCoreApplication.class);
        app.addInitializers(ctx -> {
            GenericApplicationContext g = (GenericApplicationContext) ctx;
            g.registerBean("parityTaskNumbers", ScreenParity.ResettableTaskNumbers.class,
                    ScreenParity.ResettableTaskNumbers::new, bd -> bd.setPrimary(true));
            g.registerBean(ParityControl.class);
        });
        app.run(args);
    }
}
