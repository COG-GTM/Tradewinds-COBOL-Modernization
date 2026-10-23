package com.ibm.cics.cip.bank.core.bankdata;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads baseline/seed/seed.sql and reconstructs the direct BANKDATA output from it by reversing the seed
 * activity recorded in its own PROCTRAN rows (legacy/cases/seed-activity.json): OCA rows (CREACC) remove the
 * opened account, CRE/DEB rows (DBCRFUN) subtract the amount from the account, TFR (XFRFUN) adds the amount
 * back to the from-account and subtracts it from the to-account (last 8 characters of PROCTRAN_DESC); the
 * PROCTRAN rows are dropped and ACCOUNT-COUNT / ACCOUNT-LAST recomputed.
 */
final class BaselineSeed {

    static final Path SEED_SQL = Path.of("../../baseline/seed/seed.sql");

    private BaselineSeed() {
    }

    static String read() throws IOException {
        return Files.readString(SEED_SQL, StandardCharsets.UTF_8);
    }

    static String bankdataOnly(String seedSql) {
        List<String> lines = seedSql.lines().toList();
        Set<String> opened = new HashSet<>();
        Map<String, BigDecimal> delta = new HashMap<>();
        for (String line : lines) {
            if (!line.startsWith("INSERT INTO PROCTRAN ")) {
                continue;
            }
            List<String> v = values(line);
            String number = unquote(v.get(2));
            String type = unquote(v.get(6));
            String desc = unquote(v.get(7));
            BigDecimal amount = new BigDecimal(v.get(8));
            switch (type) {
                case "OCA" -> opened.add(number);
                case "CRE", "DEB" -> delta.merge(number, amount, BigDecimal::add);
                case "TFR" -> {
                    delta.merge(number, amount.negate(), BigDecimal::add);
                    delta.merge(desc.substring(desc.length() - 8), amount, BigDecimal::add);
                }
                default -> throw new IllegalStateException("unexpected PROCTRAN type " + type);
            }
        }
        List<String> out = new ArrayList<>();
        long accounts = 0;
        long lastAccount = 0;
        for (String line : lines) {
            if (line.startsWith("INSERT INTO PROCTRAN ")) {
                continue;
            }
            if (line.startsWith("INSERT INTO ACCOUNT ")) {
                List<String> v = values(line);
                String number = unquote(v.get(3));
                if (opened.contains(number)) {
                    continue;
                }
                BigDecimal change = delta.getOrDefault(number, BigDecimal.ZERO);
                v.set(10, new BigDecimal(v.get(10)).subtract(change).toPlainString());
                v.set(11, new BigDecimal(v.get(11)).subtract(change).toPlainString());
                line = line.substring(0, line.indexOf(" VALUES (") + 9) + String.join(", ", v) + ");";
                accounts++;
                lastAccount = Math.max(lastAccount, Long.parseLong(number));
            }
            out.add(line);
        }
        for (int i = 0; i < out.size(); i++) {
            String line = out.get(i);
            if (line.contains("'987654-ACCOUNT-COUNT'")) {
                out.set(i, line.replaceFirst("'987654-ACCOUNT-COUNT', \\d+,", "'987654-ACCOUNT-COUNT', " + accounts + ","));
            } else if (line.contains("'987654-ACCOUNT-LAST'")) {
                out.set(i, line.replaceFirst("'987654-ACCOUNT-LAST', \\d+,", "'987654-ACCOUNT-LAST', " + lastAccount + ","));
            }
        }
        return String.join("\n", out) + "\n";
    }

    /** Whitespace-normalised: runs of whitespace collapsed to one space, blank lines dropped. */
    static String normalise(String sql) {
        return String.join("\n", sql.lines().map(l -> l.trim().replaceAll("\\s+", " "))
                .filter(l -> !l.isEmpty()).toList());
    }

    static List<String> lines(String sql, String table) {
        return sql.lines().filter(l -> l.startsWith("INSERT INTO " + table + " ")).toList();
    }

    /** Values of a one-line {@code INSERT ... VALUES (...);} (quoted strings may contain commas). */
    static List<String> values(String line) {
        String body = line.substring(line.indexOf(" VALUES (") + 9, line.lastIndexOf(");"));
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
                cur.append(ch);
            } else if (ch == ',' && !quoted) {
                result.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        result.add(cur.toString().trim());
        return result;
    }

    static String unquote(String value) {
        return value.startsWith("'") ? value.substring(1, value.length() - 1).replace("''", "'") : value;
    }
}
