package com.ibm.cics.cip.bank.core.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Opt-in: runs the main REST flows over HTTP against a real PostgreSQL with profile {@code postgres}.
 * Enabled only when {@code CBSA_PG_IT_URL} is set (e.g. {@code jdbc:postgresql://localhost:5432/cbsa});
 * {@code CBSA_PG_IT_USER} (default {@code cbsa}) and {@code CBSA_PG_IT_PASSWORD} supply credentials.
 * The schema named by {@code CBSA_PG_IT_SCHEMA} (default {@code cbsa_it}) is dropped and recreated so
 * schema.sql and the canonical seed load into an empty database. See docs/migration/postgres.md.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "cbsa.clock.fixed=2026-01-15T10:30:00Z",
        "cbsa.random.seed=42",
        "cbsa.seed.mode=if-empty"})
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "CBSA_PG_IT_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class PostgresProfileMainFlowsTest {

    private static final int SEED_CUSTOMERS = 30;
    private static final int SEED_ACCOUNTS = 76;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) throws Exception {
        String url = System.getenv("CBSA_PG_IT_URL");
        String user = envOr("CBSA_PG_IT_USER", "cbsa");
        String password = envOr("CBSA_PG_IT_PASSWORD", "");
        String schema = envOr("CBSA_PG_IT_SCHEMA", "cbsa_it");
        if (!schema.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("CBSA_PG_IT_SCHEMA must be a lower-case SQL identifier: " + schema);
        }
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            s.execute("CREATE SCHEMA " + schema);
        }
        String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        registry.add("spring.datasource.url", () -> schemaUrl);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    private ResponseEntity<Map> get(String path) {
        return http.getForEntity("/banking" + path, Map.class);
    }

    private ResponseEntity<Map> send(HttpMethod method, String path, Object body) {
        return http.exchange("/banking" + path, method, body == null ? null : new HttpEntity<>(body), Map.class);
    }

    private static BigDecimal dec(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private long control(String name) {
        return jdbc.queryForObject("SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME = ?", Long.class, name);
    }

    @Test
    @Order(1)
    void runsOnPostgresWithCanonicalSchemaAndSeed() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(c.getMetaData().getDriverName()).contains("PostgreSQL");
        }
        Map<String, String> types = new java.util.HashMap<>();
        jdbc.query("SELECT table_name, column_name, data_type FROM information_schema.columns"
                + " WHERE table_schema = current_schema()",
                rs -> {
                    types.put(rs.getString(1).toUpperCase() + "." + rs.getString(2).toUpperCase(), rs.getString(3));
                });
        assertThat(types).containsEntry("CUSTOMER.CUSTOMER_DATE_OF_BIRTH", "date")
                .containsEntry("CUSTOMER.CUSTOMER_CREDIT_SCORE", "integer")
                .containsKeys("ACCOUNT.ACCOUNT_AVAILABLE_BALANCE", "PROCTRAN.PROCTRAN_REF", "CONTROL.CONTROL_VALUE_NUM");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM CUSTOMER", Integer.class)).isEqualTo(SEED_CUSTOMERS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Integer.class)).isEqualTo(SEED_ACCOUNTS);
        assertThat(control("987654-CUSTOMER-LAST")).isEqualTo(SEED_CUSTOMERS);
        assertThat(control("987654-ACCOUNT-LAST")).isEqualTo(SEED_ACCOUNTS);
    }

    @Test
    @Order(2)
    void readFlowsServeTheSeed() {
        assertThat(get("/sortCode").getBody()).containsEntry("sortCode", "987654");
        assertThat(get("/companyName").getBody()).containsEntry("companyName", "CICS Bank Sample Application");

        ResponseEntity<Map> customer = get("/customer/1");
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(customer.getBody()).containsEntry("id", "0000000001")
                .containsEntry("customerName", "Drs Adam N Renton")
                .containsEntry("customerAddress", "18 Rowan Boulevard, Leicester")
                .containsEntry("dateOfBirth", "1967-10-21")
                .containsEntry("customerCreditScore", "397")
                .containsEntry("customerCreditScoreReviewDate", "2026-02-04");

        Map byName = get("/customer/name?name=Renton&limit=10").getBody();
        assertThat(byName).containsEntry("numberOfCustomers", 2);
        assertThat((List<Map>) byName.get("customers")).extracting(m -> m.get("id"))
                .containsExactly("0000000001", "0000000007");

        assertThat(get("/customer?countOnly=true").getBody()).containsEntry("numberOfCustomers", SEED_CUSTOMERS);
        assertThat(get("/account?countOnly=true").getBody()).containsEntry("numberOfAccounts", SEED_ACCOUNTS);

        Map accounts = get("/account/retrieveByCustomerNumber/1").getBody();
        assertThat(accounts).containsEntry("customerFound", true).containsEntry("numberOfAccounts", 4);
        assertThat((List<Map>) accounts.get("accounts")).extracting(m -> m.get("id"), m -> m.get("accountType"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("00000001", "ISA"),
                        org.assertj.core.groups.Tuple.tuple("00000002", "SAVING"),
                        org.assertj.core.groups.Tuple.tuple("00000003", "CURRENT"),
                        org.assertj.core.groups.Tuple.tuple("00000004", "LOAN"));

        Map account = get("/account/1").getBody();
        assertThat(account).containsEntry("accountType", "ISA").containsEntry("customerNumber", "0000000001");
        assertThat(dec(account.get("interestRate"))).isEqualByComparingTo("2.10");
        assertThat(dec(account.get("availableBalance"))).isEqualByComparingTo("623375.58");

        ResponseEntity<Map> missing = get("/customer/99999");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).containsEntry("failCode", "1");
        assertThat(get("/account/55555555").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // INQACC: account 99999999 means "last account" (highest ACCOUNT_NUMBER).
        assertThat(get("/account/99999999").getBody()).containsEntry("id", String.format("%08d", SEED_ACCOUNTS));
    }

    @Test
    @Order(3)
    void customerAndAccountLifecycleWithMoneyMovement() {
        long proctranBefore = jdbc.queryForObject("SELECT COUNT(*) FROM PROCTRAN", Long.class);

        ResponseEntity<Map> created = send(HttpMethod.POST, "/customer", Map.of(
                "customerName", "Mr Alan T Turing", "customerAddress", "1 Bletchley Park, Milton Keynes",
                "dateOfBirth", "1912-06-23", "sortCode", "987654"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String customerId = (String) created.getBody().get("id");
        assertThat(customerId).isEqualTo(String.format("%010d", SEED_CUSTOMERS + 1));
        assertThat(created.getBody()).containsEntry("dateOfBirth", "1912-06-23").containsKey("customerCreditScore");

        ResponseEntity<Map> updated = send(HttpMethod.PUT, "/customer/" + customerId, Map.of(
                "customerName", "Dr Alan T Turing", "customerAddress", "Hollymeade, Wilmslow", "sortCode", "987654"));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/customer/" + customerId).getBody()).containsEntry("customerName", "Dr Alan T Turing")
                .containsEntry("customerAddress", "Hollymeade, Wilmslow");

        ResponseEntity<Map> opened = send(HttpMethod.POST, "/account", Map.of(
                "customerNumber", customerId, "accountType", "CURRENT", "interestRate", "1.5",
                "overdraft", "250", "sortCode", "987654", "dateOpened", "2001-01-01"));
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String accountId = (String) opened.getBody().get("id");
        assertThat(accountId).isEqualTo(String.format("%08d", SEED_ACCOUNTS + 1));
        assertThat(opened.getBody()).containsEntry("dateOpened", "2026-01-15")
                .containsEntry("nextStatementDate", "2026-02-14");

        Map account = get("/account/" + accountId).getBody();
        account.put("accountType", "SAVING");
        account.put("interestRate", 1.25);
        account.put("overdraft", 0);
        ResponseEntity<Map> accountUpdated = send(HttpMethod.PUT, "/account/" + accountId, account);
        assertThat(accountUpdated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accountUpdated.getBody()).containsEntry("accountType", "SAVING");
        assertThat(dec(accountUpdated.getBody().get("interestRate"))).isEqualByComparingTo("1.25");

        ResponseEntity<Map> credited = send(HttpMethod.PUT, "/account/credit/" + accountId, Map.of("amount", 100.00));
        assertThat(credited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dec(credited.getBody().get("actualBalance"))).isEqualByComparingTo("100.00");

        ResponseEntity<Map> debited = send(HttpMethod.PUT, "/account/debit/" + accountId, Map.of("amount", 30.25));
        assertThat(debited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dec(debited.getBody().get("availableBalance"))).isEqualByComparingTo("69.75");

        ResponseEntity<Map> transfer = send(HttpMethod.PUT, "/account/transfer/" + accountId,
                Map.of("targetAccount", 1, "amount", 20.00));
        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dec(((Map) transfer.getBody().get("fromAccount")).get("actualBalance"))).isEqualByComparingTo("49.75");
        assertThat(dec(((Map) transfer.getBody().get("toAccount")).get("actualBalance")))
                .isEqualByComparingTo("623395.58");

        // OCC, OCA, CRE, DEB, TFR written to PROCTRAN.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PROCTRAN", Long.class)).isEqualTo(proctranBefore + 5);
        assertThat(jdbc.queryForList("SELECT PROCTRAN_TYPE FROM PROCTRAN WHERE PROCTRAN_NUMBER = ?", String.class,
                accountId)).contains("OCA", "CRE", "DEB", "TFR");
        assertThat((List<Map>) get("/processedTransaction?limit=100").getBody().get("processedTransactions"))
                .isNotEmpty();

        ResponseEntity<Map> badType = send(HttpMethod.POST, "/account", Map.of(
                "customerNumber", customerId, "accountType", "BOGUS", "interestRate", "0",
                "overdraft", "0", "sortCode", "987654"));
        assertThat(badType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badType.getBody()).containsEntry("failCode", "A");

        assertThat(send(HttpMethod.DELETE, "/account/" + accountId, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/account/" + accountId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        send(HttpMethod.POST, "/account", Map.of("customerNumber", customerId, "accountType", "ISA",
                "interestRate", "2", "overdraft", "0", "sortCode", "987654"));
        assertThat(get("/account/retrieveByCustomerNumber/" + customerId).getBody())
                .containsEntry("numberOfAccounts", 1);

        ResponseEntity<Map> deleted = send(HttpMethod.DELETE, "/customer/" + customerId, null);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/customer/" + customerId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_CUSTOMER_NUMBER = ?", Long.class,
                customerId)).isZero();
    }

    @Test
    @Order(4)
    void concurrentCreatesGetDistinctNumbersFromControl() throws Exception {
        long customerLast = control("987654-CUSTOMER-LAST");
        long accountLast = control("987654-ACCOUNT-LAST");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<ResponseEntity<Map>>> customers = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                customers.add(() -> send(HttpMethod.POST, "/customer", Map.of(
                        "customerName", "Ms Para L Lel" + n, "customerAddress", n + " Thread Street, Leeds",
                        "dateOfBirth", "1990-01-0" + (n + 1), "sortCode", "987654")));
            }
            Set<String> customerIds = new HashSet<>();
            for (Future<ResponseEntity<Map>> f : pool.invokeAll(customers)) {
                assertThat(f.get().getStatusCode()).isEqualTo(HttpStatus.CREATED);
                customerIds.add((String) f.get().getBody().get("id"));
            }
            assertThat(customerIds).hasSize(threads);
            assertThat(control("987654-CUSTOMER-LAST")).isEqualTo(customerLast + threads);

            String owner = customerIds.iterator().next();
            List<Callable<ResponseEntity<Map>>> accounts = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                accounts.add(() -> send(HttpMethod.POST, "/account", Map.of("customerNumber", owner,
                        "accountType", "SAVING", "interestRate", "1", "overdraft", "0", "sortCode", "987654")));
            }
            Set<String> accountIds = new HashSet<>();
            for (Future<ResponseEntity<Map>> f : pool.invokeAll(accounts)) {
                assertThat(f.get().getStatusCode()).isEqualTo(HttpStatus.CREATED);
                accountIds.add((String) f.get().getBody().get("id"));
            }
            assertThat(accountIds).hasSize(threads);
            assertThat(control("987654-ACCOUNT-LAST")).isEqualTo(accountLast + threads);
        } finally {
            pool.shutdownNow();
        }
    }
}
