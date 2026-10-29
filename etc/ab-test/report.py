#!/usr/bin/env python3
"""Render the A/B results as a side-by-side HTML report."""
import html
import json
import sys

a_path, b_path, mig_path, out_path = sys.argv[1:5]
a, b = json.load(open(a_path)), json.load(open(b_path))
migration = open(mig_path).read()


def key(k):
    head = k.split(" ", 1)[0]
    return (int(head) if head.isdigit() else -1, k)


def fmt(v):
    return html.escape(json.dumps(v, indent=2, sort_keys=True))


# Differences that are understood and accepted, with the reason shown in the report
EXPECTED = {
    "10 Accounts by balance (>250, offset 2, limit 3)":
        "Legacy DB2 SQL numbers rows with row_number() over() (no ORDER BY in the "
        "window), so the page it returns depends on DB2's access path, not on the "
        "query's ORDER BY ACCOUNT_NUMBER. PostgreSQL returns rows 2-4 of the ordered "
        "result, which is what the method's ORDER BY intends.",
}

rows, matches, expected = [], 0, 0
steps = sorted((k for k in set(a) | set(b) if k != "database"), key=key)
for k in steps:
    same = a.get(k) == b.get(k)
    matches += same
    known = not same and k in EXPECTED
    expected += known
    badge = ('<span class="ok">MATCH</span>' if same else
             f'<span class="warn">EXPECTED DIFF</span><p class="why">{html.escape(EXPECTED[k])}</p>'
             if known else '<span class="bad">DIFF</span>')
    rows.append(f"<tr class='{'same' if same else 'known' if known else 'diff'}'><td><b>{html.escape(k)}</b><br>{badge}</td>"
                f"<td><pre>{fmt(a.get(k))}</pre></td><td><pre>{fmt(b.get(k))}</pre></td></tr>")

mig_ok = "MISMATCH" not in migration and "MATCH" in migration
passed = matches + expected == len(steps) and mig_ok
summary = (f"{matches}/{len(steps)} scenario steps identical, {expected} expected difference(s), "
           f"{len(steps) - matches - expected} unexpected; data migration "
           f"{'verified (row counts + SHA-256 match)' if mig_ok else 'FAILED'}")
open(out_path, "w").write(f"""<!doctype html><html><head><meta charset="utf-8">
<title>BankLiberty A/B: DB2 vs PostgreSQL</title><style>
body{{font-family:system-ui,sans-serif;margin:24px;background:#f6f7f9}}
h1{{margin:0 0 4px}} .sum{{font-size:18px;margin:8px 0 16px}}
table{{border-collapse:collapse;width:100%;background:#fff}}
td,th{{border:1px solid #ddd;padding:8px;vertical-align:top}} th{{background:#1f2937;color:#fff}}
td:first-child{{width:18%}} pre{{margin:0;white-space:pre-wrap;font-size:12px;max-height:340px;overflow:auto}}
.ok{{background:#16a34a;color:#fff;padding:2px 8px;border-radius:4px}}
.bad{{background:#dc2626;color:#fff;padding:2px 8px;border-radius:4px}}
tr.diff td{{background:#fef2f2}} tr.known td{{background:#fffbeb}}
.warn{{background:#d97706;color:#fff;padding:2px 8px;border-radius:4px}} .why{{font-size:12px;color:#444}} .mig{{background:#111827;color:#d1fae5;padding:12px}}
</style></head><body>
<h1>BankLiberty data layer A/B: DB2 (A) vs PostgreSQL (B)</h1>
<div class="sum"><span class="{'ok' if passed else 'bad'}">{html.escape(summary)}</span></div>
<table><tr><th>Step</th><th>A: {html.escape(a.get('database', 'A'))} (original SQL)</th>
<th>B: {html.escape(b.get('database', 'B'))} (migrated SQL)</th></tr>{''.join(rows)}</table>
<h2>DB2 &rarr; PostgreSQL data migration</h2><pre class="mig">{html.escape(migration)}</pre>
</body></html>""")
print(summary)
print("Report:", out_path)
