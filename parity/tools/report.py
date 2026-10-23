#!/usr/bin/env python3
"""Builds parity/results/summary.json, parity/results/round3/ and the self-contained parity/report/parity-report.html.

Round 3 (final): the migrated side ran on H2 (default profile) and on PostgreSQL (profile postgres). Rounds 1 and 2 are read
from parity/results/round{1,2}/ for the round-by-round history. Nothing is claimed that is not in parity/results."""
import base64, datetime, fnmatch, html, io, json, os, platform, re, subprocess
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
PARITY = os.path.dirname(HERE)
ROOT = os.path.dirname(PARITY)
RES = os.path.join(PARITY, 'results')
ROUND = '3'
has = lambda n: os.path.exists(os.path.join(RES, n))
load = lambda n, d=None: json.load(open(os.path.join(RES, n))) if has(n) else d
legacy, mvn, react = load('legacy-replay-all.json'), load('tests-migrated.json'), load('tests-react.json')
pgit = load('tests-postgres-it.json')
EMPTY_UI = {'results': [], 'screenshots': []}
RUNS = {'h2': {'label': 'H2', 'flows': load('flows.json'), 'ui': load('react-ui.json', EMPTY_UI)}}
if has('flows-postgres.json'):
    RUNS['postgres'] = {'label': 'PostgreSQL', 'flows': load('flows-postgres.json'), 'ui': load('react-ui-postgres.json', EMPTY_UI)}
DBS = list(RUNS)
ROUNDS = {}
for r in ('round1', 'round2'):
    if has(r + '/summary.json'):
        ROUNDS[r] = {'summary': load(r + '/summary.json'), 'status': load(r + '/flow-status.json', {})}


def psql_version():
    """server_version of the PostgreSQL the parity run used (PARITY_PG_URL, same parsing as parity.pg_recreate_schema)."""
    m = re.match(r'jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)', os.environ.get('PARITY_PG_URL', 'jdbc:postgresql://localhost:5432/cbsa'))
    if not m:
        return 'unknown'
    try:
        return subprocess.run(['psql', '-tA', '-h', m.group(1), '-p', m.group(2) or '5432', '-U', os.environ.get('PARITY_PG_USER', 'cbsa'),
                               '-d', m.group(3), '-c', 'show server_version'],
                              env=dict(os.environ, PGPASSWORD=os.environ.get('PARITY_PG_PASSWORD', 'cbsa')),
                              capture_output=True, text=True, timeout=10).stdout.strip() or 'unknown'
    except Exception:
        return 'unknown'


# ------------------------------------------------------------------ per-database classification of this round
def analyse(db):
    flows = RUNS[db]['flows']
    by_kind = {}
    for r in flows:
        by_kind.setdefault(r['kind'], {}).setdefault(r['status'], 0)
        by_kind[r['kind']][r['status']] += 1
    comparable = [r for r in flows if r['status'] in ('MATCH', 'DIFF')]
    defects = {}
    for r in flows:
        if r['status'] == 'DIFF':  # every difference is a migration defect until someone classifies it with evidence
            defects['MD-%s-%s' % (db.upper(), r['id'])] = {
                'title': 'untriaged difference on %s (treated as a migration defect)' % RUNS[db]['label'], 'flow': r['id'],
                'field': ', '.join(sorted({d['field'] for d in r['diffs']}))[:300],
                'legacy': '; '.join(json.dumps(d['legacy'], sort_keys=True)[:200] for d in r['diffs'][:5]),
                'migrated': '; '.join(json.dumps(d['migrated'], sort_keys=True)[:200] for d in r['diffs'][:5])}
    ui = RUNS[db]['ui']['results']
    return {'flows_total': len(flows), 'flows_comparable': len(comparable),
            'flows_matching': sum(1 for r in comparable if r['status'] == 'MATCH'),
            'flows_not_comparable': sum(1 for r in flows if r['status'] == 'NOT_COMPARABLE'),
            'execution_errors': [{'id': r['id'], 'error': r.get('error')} for r in flows if r['status'] == 'ERROR'],
            'by_kind': by_kind, 'migration_defects': defects,
            'react_ui_browser': {'checks': len(ui), 'matching': sum(1 for r in ui if r['status'] == 'MATCH'),
                                 'results': [{k: r[k] for k in ('id', 'status')} for r in ui]}}


A = {db: analyse(db) for db in DBS}
STATUS = {db: {r['id']: r['status'] for r in RUNS[db]['flows']} for db in DBS}
ALL_DEFECTS = {k: v for db in DBS for k, v in A[db]['migration_defects'].items()}
react_ok = react.get('exit_code') == 0 and react.get('tests_failed', 1) == 0 and react.get('tests_total', 0) > 0
build_ok = (react.get('build') or {}).get('exit_code') == 0

# ------------------------------------------------------------------ round history r1 -> r2 -> r3
HIST_CAUSE = {
    'MD-PROCTRAN-API': ('GET /banking/processedTransaction was not implemented in the migrated service (HTTP 404 vs 8 PROCTRAN rows '
                        'served by legacy Liberty ProcessedTransactionResource).',
                        'Implemented between round 1 and round 2; MATCH since round 2.'),
    'KL-CREACC-BAL': ('POST /banking/account had no opening-balance fields: COBOL CREACC writes COMM-AVAIL-BAL/COMM-ACT-BAL '
                      '(50, 100, -5000, -125000) while the migrated account opened at 0.00.',
                      'PR #27: optional availableBalance/actualBalance on POST /banking/account (CREACC COMMAREA balances).'),
    'KL-UPDACC-TYPE': ('Migrated PUT /banking/account returned 400 for type CHEQUE; COBOL UPDACC only rejects a blank type and writes '
                       'CHEQUE (pre-existing legacy defect README #9).',
                       'PR #27: UPDACC REST accepts any non-blank type, reproducing the COBOL rule.'),
    'KL-RANDOM-CUST': ('INQCUST custno 0 ("random customer"): legacy RANDOM(EIBTASKN) + GnuCOBOL -std=ibm COMPUTE collapses to customer '
                       '0000000001; the migrated service drew from CbsaRandom (0000000022 for seed 42).',
                       'PR #27: reproduces RANDOM(EIBTASKN) and the -std=ibm decimal alignment (drops 9 low digits).'),
    'ABNDPROC': ('ABNDPROC had no REST trigger, so the flow was NOT_COMPARABLE.',
                 'PR #27: AbendFile (ABNDFILE port) + property-gated, token-protected POST/GET /banking/admin/abend; '
                 'response and ABNDFILE contents compared.'),
}


def hist_class(rnd, fid):
    s = ROUNDS.get(rnd)
    if not s:
        return None
    c = (s['summary'].get('classification') or {}).get(fid)
    if not c and s['status'].get(fid) == 'NOT_COMPARABLE' and fid.startswith('ABNDPROC'):
        c = 'ABNDPROC'
    return c


hist_ids = [r['id'] for r in RUNS['h2']['flows'] if any(ROUNDS[x]['status'].get(r['id'], 'MATCH') != 'MATCH' for x in ROUNDS)
            or any(STATUS[db].get(r['id']) != 'MATCH' for db in DBS)]
HISTORY = []
for fid in hist_ids:
    c = next((hist_class(x, fid) for x in ('round2', 'round1') if hist_class(x, fid)), None)
    cause, fix = HIST_CAUSE.get(c, ('not classified', '-'))
    HISTORY.append({'id': fid, 'round1': ROUNDS.get('round1', {}).get('status', {}).get(fid),
                    'round2': ROUNDS.get('round2', {}).get('status', {}).get(fid), 'class_before': c,
                    **{'round3_' + db: STATUS[db].get(fid) for db in DBS}, 'root_cause': cause, 'resolution': fix})


def round_row(name, s, extra):
    oth = ' '.join(str(x) for x in (s.get('known_limitations') or {}).get('other', []))
    rt = ((s.get('tests') or {}).get('react') or {})
    return {'round': name, 'head': (s.get('head') or '')[:7], 'generated': s.get('generated'), 'flows_total': s.get('flows_total'),
            'flows_comparable': s.get('flows_comparable'), 'flows_matching': s.get('flows_matching'),
            'flows_not_comparable': s.get('flows_not_comparable'),
            'migration_defects': sorted(s.get('migration_defects') or {}),
            'known_difference_flows': sum(1 for v in (s.get('classification') or {}).values() if str(v).startswith('KL-')),
            'proctran_ref': 'excluded' if re.search(r'PROCTRAN_REF[^.]*(exclu|not compared)', oth, re.I) or 'PROCTRAN_REF' in str(s.get('excluded_columns', '')) else 'compared',
            'react_unit_tests': rt.get('tests') or '-', **extra}


# ------------------------------------------------------------------ pre-existing legacy defects (baseline/README.md)
README = open(os.path.join(ROOT, 'baseline', 'README.md')).read()
sec = README.split('## Legacy defects and oddities observed (not fixed)', 1)[1].split('\n## ', 1)[0]
LEG = []
for m in re.finditer(r'^(\d+)\. (.*?)(?=^\d+\. |\Z)', sec, re.M | re.S):
    LEG.append((int(m.group(1)), ' '.join(m.group(2).replace('**', '').replace('`', '').split())))
LEG_FLOWS = {1: ['ABNDPROC/record'], 2: ['DBCRFUN/*-payment-api', 'DBCRFUN/within-overdraft'], 3: ['DBCRFUN/*-teller', 'TELLER-REST/*'],
             4: ['DBCRFUN/loan-*', 'DBCRFUN/mortgage-*', 'TELLER-REST/loan-teller'], 5: ['DBCRFUN/zero-amount'],
             6: ['XFRFUN/insufficient-funds'], 7: ['XFRFUN/same-account'], 8: ['XFRFUN/to-not-found'], 9: ['UPDACC/invalid-type'],
             10: ['UPDCUST/ignores-dob-and-score'], 11: ['BNK1DCS/lookup-non-numeric'], 12: ['BNK1CAC/*'], 13: [],
             14: ['CRECUST/ok-*'], 15: ['CRECUST/ok-seed-1234-taskn-500'], 16: [], 17: ['INQACCCU/no-accounts', 'INQACCCU/customer-not-found'],
             18: ['CREACC/lowercase-type', 'CREACC/ninth-account', 'CREACC/max-accounts']}
EXTRA_LEG = [('round 2', 'INQCUST custno 0 ("random customer") always returns customer 0000000001 under the harness, whatever '
              'CBSA_RANDOM_SEED (GnuCOBOL -std=ibm COMPUTE truncation of RANDOM(EIBTASKN)); BNK1DCS non-numeric input inherits it.',
              ['INQCUST/random-seed-42', 'INQCUST/random-seed-7', 'BNK1DCS/lookup-non-numeric'])]


LEG_UNEXERCISED = {13: 'no flow exercises it directly (BANKDATA output = canonical seed, verified identical before every flow)',
                   16: 'not exercised: no transcript forces the DELCUS VSAM-delete failure that writes this abend text'}


def leg_status(pats, n=None):
    ids = [f for f in STATUS['h2'] if any(fnmatch.fnmatch(f, p) for p in pats)]
    if not ids:
        return ids, LEG_UNEXERCISED.get(n, 'no matching flow')
    bad = ['%s %s=%s' % (f, RUNS[db]['label'], STATUS[db][f]) for f in ids for db in DBS if STATUS[db].get(f) != 'MATCH']
    return ids, ('every flow exercising it is identical (legacy behaviour reproduced) on %s' % ' and '.join(RUNS[d]['label'] for d in DBS)) if not bad else 'NOT identical: ' + ', '.join(bad)


LEGACY_DEFECTS = []
for n, t in LEG:
    ids, st = leg_status(LEG_FLOWS.get(n, []), n)
    LEGACY_DEFECTS.append({'ref': 'README #%d' % n, 'text': t, 'flows': ids, 'parity': st})
for ref, t, pats in EXTRA_LEG:
    ids, st = leg_status(pats)
    LEGACY_DEFECTS.append({'ref': 'new in ' + ref, 'text': t, 'flows': ids, 'parity': st})

# ------------------------------------------------------------------ harness findings of this round + coverage limitations
HARNESS_NOTES = [
    'First PostgreSQL run: CREACC/max-accounts and CREACC/ninth-account ended in ERROR before comparison because the harness translated '
    'their SQLite setup_sql (recursive CTE) to H2-only SYSTEM_RANGE; the migrated service correctly returned 500 for that statement. '
    'Fixed in parity/tools/parity.py (sqlite_to_migrated: generate_series on PostgreSQL). Both flows MATCH on PostgreSQL after the fix. '
    'Harness defect, not a migration defect.',
    'PostgreSQL folds the unquoted legacy column names of the in-process 3270 dumps to lower case; parity/tools/ScreenParity.java '
    'upper-cases dump keys so the CUSTOMER/ACCOUNT/PROCTRAN/CONTROL dumps compare column by column.',
]
LIMITATIONS = [
    'Legacy ran under GnuCOBOL 3.1.2 + the C/SQLite CICS/DB2/VSAM stub runtime (legacy/), not on z/OS CICS/DB2/VSAM. Parity is '
    'against that harness (107/107 baseline transcripts replay with no golden fixtures).',
    'The migrated side is driven through a parity-only test seam (parity/tools/ParityServer.java: /__parity reset, EIBTASKN restart, '
    'exec, dump); all business beans are the unmodified target/classes. ABNDPROC/record uses the property-gated, token-protected '
    'POST/GET /banking/admin/abend, enabled only on the parity server (disabled by default).',
    'BANKDATA (batch) is not replayed per flow: its output is the canonical seed, and the seeded CUSTOMER/ACCOUNT/PROCTRAN/CONTROL dumps '
    'of both sides were verified identical before every flow on both databases.',
    '3270 screen flows (BNKMENU/BNK1*) have no REST surface: they run in-process through the com.ibm.cics.cip.bank.core.screens handlers '
    '(parity/tools/ScreenParity.java). Screen fields and table dumps are compared; RETURN COMMAREA bytes are not (Java keeps a symbolic state map).',
    'React UI in Chromium covers the read paths (customer by number, accounts of a customer, name search, account by number) on both '
    'databases. Create/update/delete screens were not driven in the browser in this round; their REST calls are covered by the API '
    'flows and by the React axios contract tests. (PR #26 reported a browser CRUD run on PostgreSQL; not re-observed here.)',
    'Pre-existing UI quirk (production React source, unchanged): CustomerDetailsPage Submit toggles table visibility, so a consecutive '
    'search needs a second Submit.',
    'PostgreSQL: flows run serially, one fresh schema per parity server (DROP/CREATE before start); concurrency on PostgreSQL is covered '
    'only by the opt-in PostgresProfileMainFlowsTest (concurrent counter allocation).',
    'Comparison normalisation: CHAR right-trim, numbers to 2dp, row order. Nothing else is masked; PROCTRAN_REF and every other column is compared.',
]

# ------------------------------------------------------------------ summary json + round3 snapshot
head = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, text=True).stdout.strip()
now = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
PGV = psql_version() if 'postgres' in DBS else None
H2A = A['h2']
summary = {
    'round': ROUND, 'generated': now, 'head': head, 'databases': DBS, 'postgres_server_version': PGV,
    # top-level numbers are the H2 run (default profile); per-database results below
    'flows_total': H2A['flows_total'], 'flows_comparable': H2A['flows_comparable'], 'flows_matching': H2A['flows_matching'],
    'flows_not_comparable': H2A['flows_not_comparable'], 'by_kind': H2A['by_kind'], 'execution_errors': H2A['execution_errors'],
    'per_database': A, 'classification': {}, 'excluded_columns': [],
    'migration_defects': ALL_DEFECTS,
    'known_limitations': {'other': LIMITATIONS}, 'harness_findings': HARNESS_NOTES,
    'pre_existing_legacy_defects': LEGACY_DEFECTS,
    'tests': {'legacy_transcripts': legacy['totals'] | {'total': legacy['total']},
              'migrated_mvn': mvn['totals'] | {'classes': mvn['classes']},
              'postgres_it': pgit,
              'react': {k: react.get(k) for k in ('command', 'exit_code', 'suites', 'tests', 'tests_passed', 'tests_failed', 'tests_total',
                                                  'suites_total', 'test_files', 'build')}},
    'history': {'rounds': [], 'flows': HISTORY},
}
for name, r in ROUNDS.items():
    summary['history']['rounds'].append(round_row(name, r['summary'], {'database': 'H2', 'postgres': 'not exercised'}))
for db in DBS:
    summary['history']['rounds'].append({'round': 'round3 ' + RUNS[db]['label'], 'head': head[:7], 'generated': now,
                                         **{k: A[db][k] for k in ('flows_total', 'flows_comparable', 'flows_matching', 'flows_not_comparable')},
                                         'execution_errors': len(A[db]['execution_errors']),
                                         'migration_defects': sorted(A[db]['migration_defects']), 'known_difference_flows': 0,
                                         'proctran_ref': 'compared', 'react_unit_tests': react.get('tests') or '-', 'database': RUNS[db]['label']})
json.dump(summary, open(os.path.join(RES, 'summary.json'), 'w'), indent=1)
os.makedirs(os.path.join(RES, 'round3'), exist_ok=True)
json.dump(summary, open(os.path.join(RES, 'round3', 'summary.json'), 'w'), indent=1)
json.dump({db: STATUS[db] for db in DBS}, open(os.path.join(RES, 'round3', 'flow-status.json'), 'w'), indent=1, sort_keys=True)


# ------------------------------------------------------------------ images (terminal captures)
def font(sz=14):
    for p in ('/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf', '/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf'):
        if os.path.exists(p):
            return ImageFont.truetype(p, sz)
    return ImageFont.load_default()


def text_png(lines, title, fg=(51, 255, 102), bg=(10, 14, 10), width=96):
    f = font()
    lines = [title, '-' * min(width, max(len(title), 20))] + [l[:width] for l in lines]
    w, h = 9 * width + 24, 18 * len(lines) + 24
    im = Image.new('RGB', (w, h), bg)
    d = ImageDraw.Draw(im)
    for i, l in enumerate(lines):
        d.text((12, 12 + 18 * i), l, font=f, fill=(255, 255, 255) if i == 0 else fg)
    buf = io.BytesIO()
    im.save(buf, 'PNG', optimize=True)
    return 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()


def screen_lines(evs):
    return ['  %-10s %s' % (e[1], e[2]) if e[0] == 'FIELD' else ' '.join(str(x) for x in e) for e in evs]


images = []
for db in DBS:
    F = {r['id']: r for r in RUNS[db]['flows']}
    for fid in (('BNK1DCS/lookup-found', 'BNK1TFN/transfer-ok', 'BNK1DCS/lookup-non-numeric') if db == 'h2' else ('BNK1TFN/transfer-ok',)):
        st = F[fid]['steps'][0]
        if 'legacy_screen' in st and 'migrated_screen' in st:
            images.append(('%s on %s (%s)' % (fid, RUNS[db]['label'], F[fid]['status']), text_png(screen_lines(st['legacy_screen']), 'LEGACY GnuCOBOL ' + fid),
                           text_png(screen_lines(st['migrated_screen']), 'MIGRATED Java 21 / %s %s' % (RUNS[db]['label'], fid), fg=(120, 200, 255))))
consoles = []
for db in DBS:
    p = os.path.join(PARITY, 'build', 'parity-%s.log' % db)
    if os.path.exists(p):
        lines = [l.rstrip() for l in open(p)]
        consoles.append((db, text_png(lines[-40:], '$ PARITY_DB=%s parity/tools/parity.py   (last 40 lines)' % db, width=100)))

# ------------------------------------------------------------------ html
E = lambda s: html.escape(str(s))
J = lambda v: E(json.dumps(v, sort_keys=True)) if not isinstance(v, str) else E(v)


def env_versions():
    run = lambda c: (subprocess.run(c, shell=True, capture_output=True, text=True).stdout.strip().splitlines() or [''])[:1]
    v = [('GnuCOBOL', run('cobc --version')), ('Java', run('/usr/lib/jvm/java-21-openjdk-amd64/bin/java -version 2>&1')),
         ('Maven', run('mvn -v 2>/dev/null')), ('Spring Boot', ['3.3.4']), ('H2', ['2.2.224 (MODE=PostgreSQL, in-memory)'])]
    if PGV:
        v.append(('PostgreSQL', [PGV + ' (docker postgres:16, profile postgres, one schema per parity server)']))
    return v + [('SQLite (legacy stub DB)', run('python3 -c "import sqlite3;print(sqlite3.sqlite_version)"')),
                ('Python', [platform.python_version()]), ('Node', run('node --version')), ('Yarn', run('cd %s/src/bank-application-frontend && corepack yarn --version' % ROOT)),
                ('Chromium (Playwright)', [RUNS['h2']['ui'].get('browser', '')]), ('OS', [platform.platform()])]


css = '''body{font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;margin:0;color:#161616;background:#f4f4f4}
header{background:#161616;color:#fff;padding:24px 40px}header h1{margin:0;font-size:24px}header p{margin:6px 0 0;color:#c6c6c6}
main{padding:24px 40px;max-width:1500px}section{background:#fff;padding:20px 24px;margin:0 0 20px;border-left:4px solid #0f62fe}
h2{margin-top:0}table{border-collapse:collapse;width:100%;font-size:13px}th,td{border:1px solid #e0e0e0;padding:5px 8px;text-align:left;vertical-align:top}
th{background:#e8e8e8}.MATCH{color:#0e6027;font-weight:600}.DIFF,.ERROR{color:#a2191f;font-weight:600}.NOT_COMPARABLE{color:#8a3800;font-weight:600}
.cards{display:flex;gap:16px;flex-wrap:wrap}.card{background:#fff;border:1px solid #e0e0e0;padding:14px 18px;min-width:170px}
.card b{display:block;font-size:28px}.ok{border-left:4px solid #24a148}.bad{border-left:4px solid #da1e28}.warn{border-left:4px solid #f1c21b}
.lim{border-left-color:#f1c21b}.def{border-left-color:#da1e28}.ver{border-left-color:#24a148}.leg{border-left-color:#8a3ffc}
code,pre{font-family:Menlo,Consolas,monospace;font-size:12px}pre{background:#f4f4f4;padding:8px;overflow:auto;max-height:260px}
img{max-width:100%;border:1px solid #393939}.pair{display:grid;grid-template-columns:1fr 1fr;gap:12px}small{color:#525252}
input{padding:6px;width:320px;margin:0 0 8px}'''


def cls(ok):
    return 'ok' if ok else 'bad'


H = ['<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>CBSA parity report - round 3 (final)</title><style>%s</style></head><body>' % css]
H.append('<header><h1>CBSA legacy (COBOL/CICS) vs Java 21 migration &mdash; end-to-end parity, round 3 (final)</h1>'
         '<p>devin/java21-cbsa-core @ %s &middot; migrated side on %s &middot; generated %s &middot; produced by parity/run-parity.sh</p></header><main>'
         % (E(head[:12]), E(' and '.join(RUNS[d]['label'] for d in DBS)), E(now)))
cards = []
for db in DBS:
    a = A[db]
    ok = a['flows_matching'] == a['flows_total'] and not a['execution_errors']
    cards.append('<div class="card %s"><b>%d / %d</b>flows identical on %s</div>' % (cls(ok), a['flows_matching'], a['flows_total'], RUNS[db]['label']))
if 'postgres' not in DBS:
    cards.append('<div class="card bad"><b>not run</b>PostgreSQL profile</div>')
cards += ['<div class="card %s"><b>%d</b>migration defects</div>' % (cls(not ALL_DEFECTS), len(ALL_DEFECTS)),
          '<div class="card %s"><b>%d / %d</b>legacy transcripts replay</div>' % (cls(legacy['totals']['match'] == legacy['total']), legacy['totals']['match'], legacy['total']),
          '<div class="card %s"><b>%d</b>mvn tests (%d F, %d E, %d skipped)</div>' % (cls(not (mvn['totals']['failures'] or mvn['totals']['errors'])), mvn['totals']['tests'], mvn['totals']['failures'], mvn['totals']['errors'], mvn['totals']['skipped'])]
if pgit:
    cards.append('<div class="card %s"><b>%d / %d</b>PostgreSQL integration tests</div>' % (cls(not (pgit['failures'] or pgit['errors'] or pgit['skipped'])), pgit['tests'] - pgit['failures'] - pgit['errors'] - pgit['skipped'], pgit['tests']))
cards += ['<div class="card %s"><b>%s / %s</b>React unit tests</div>' % (cls(react_ok), react.get('tests_passed'), react.get('tests_total')),
          '<div class="card %s"><b>%s</b>yarn build</div>' % (cls(build_ok), 'green' if build_ok else 'FAILED')]
for db in DBS:
    u = A[db]['react_ui_browser']
    cards.append('<div class="card %s"><b>%d / %d</b>React UI checks in Chromium (%s)</div>' % (cls(u['checks'] and u['matching'] == u['checks']), u['matching'], u['checks'], RUNS[db]['label']))
H.append('<div class="cards">' + ''.join(cards) + '</div><br>')

# 0. history
H.append('<section><h2>0. Round-by-round history (r1 &rarr; r2 &rarr; r3)</h2><table><tr><th>Round</th><th>Head</th><th>Migrated DB</th>'
         '<th>Flows total</th><th>Comparable</th><th>Identical</th><th>Not comparable</th><th>Known-difference flows</th>'
         '<th>Migration defects</th><th>PROCTRAN_REF</th><th>React unit tests</th></tr>')
for r in summary['history']['rounds']:
    H.append('<tr>' + ''.join('<td>%s</td>' % E(x) for x in (
        r['round'], r['head'], r['database'], r['flows_total'], r['flows_comparable'], r['flows_matching'], r['flows_not_comparable'],
        r['known_difference_flows'], ', '.join(r['migration_defects']) or 'none', r['proctran_ref'], r['react_unit_tests'])) + '</tr>')
H.append('</table><br><table><tr><th>Flow</th><th>Round 1</th><th>Round 2</th>' + ''.join('<th>Round 3 %s</th>' % RUNS[d]['label'] for d in DBS) +
         '<th>Class before r3</th><th>Root cause of the difference</th><th>Resolution</th></tr>')
for h in HISTORY:
    st = lambda s: '<td class="%s">%s</td>' % (s or '', E(s or 'absent'))
    H.append('<tr><td>%s</td>%s%s%s<td>%s</td><td>%s</td><td>%s</td></tr>' % (
        E(h['id']), st(h['round1']), st(h['round2']), ''.join(st(h['round3_' + d]) for d in DBS), E(h['class_before'] or '-'), E(h['root_cause']), E(h['resolution'])))
H.append('</table><p><small>All other flows were MATCH in every round in which they existed. Round-level changes outside the flow table: '
         'round 3 compares PROCTRAN_REF (CICS EIBTASKN per task, PR #27); React unit tests run (PR #26); the PostgreSQL profile is '
         'exercised by the full parity run for the first time (this round).</small></p></section>')

# 1. scope
nk = lambda k: sum(A['h2']['by_kind'].get(k, {}).values())
H.append('<section><h2>1. Scope</h2><ul>'
         '<li><b>All %d baseline transcripts</b> (baseline/transcripts): %d LINK/COMMAREA transcripts replayed on legacy via the GnuCOBOL '
         'harness and on the migrated service via the REST API of docs/migration/api-contract.md; %d terminal-mode (BNKMENU/BNK1*) '
         'transcripts replayed through the migrated 3270 screen handlers.</li>'
         '<li><b>%d API variants</b>: every DBCRFUN teller transcript re-run through <code>PUT /banking/account/{debit|credit}/{id}</code>.</li>'
         '<li><b>%d multi-step user journeys</b> (parity/flows/user-flows.json), compared after <i>every</i> step.</li>'
         '<li><b>%d React read endpoints</b> on the Liberty layer cross-checked against the same query on the legacy seeded DB.</li>'
         '<li>Per flow: outcome (success / fail code / ABEND code), every business response field, DB delta of CUSTOMER, ACCOUNT, PROCTRAN, '
         'CONTROL and the full table dumps after the flow (PROCTRAN_REF included).</li>'
         '<li>The whole flow set runs twice: migrated side on <b>H2</b> (default profile) and on <b>PostgreSQL</b> (profile postgres); the legacy '
         'side is re-run for each flow in both passes.</li>'
         '<li><b>React</b>: unit tests (react-scripts/Jest), production build, and the built UI served by the migrated service driven in headless '
         'Chromium against each database (section 6).</li></ul></section>'
         % (legacy['total'], nk('transcript-link'), nk('transcript-terminal'), nk('api-variant'), nk('user-flow'), nk('api-crosscheck')))

# 2. environment
H.append('<section><h2>2. Environment and exactly how each side ran</h2><table><tr><th>Component</th><th>Version</th></tr>')
for k, v in env_versions():
    H.append('<tr><td>%s</td><td>%s</td></tr>' % (E(k), E(' '.join(v))))
H.append('</table><br><table><tr><th>Side</th><th>How it ran</th></tr>'
         '<tr><td>Legacy</td><td>legacy/build.sh compiles all 29 programs of src/base/cobol_src with GnuCOBOL 3.1.2 (-std=ibm) against the C/SQLite '
         'stub runtime (legacy/runtime/cbsastub.c); legacy/run-all.sh replays the baseline (%d match, %d diff, %d golden fixtures). For parity, '
         'every flow gets a fresh SQLite DB from baseline/seed/seed.sql and runs the real COBOL programs (legacy/tools/harness.py, same semantics '
         'as legacy/run-transcript.sh). Env CBSA_FIXED_TIME=2026-01-15T10:30:00Z, CBSA_RANDOM_SEED=42 (7 / 1234 where the transcript says so), '
         'CBSA_TASKN=100 (or per transcript / journey step).</td></tr>'
         '<tr><td>Migrated, H2</td><td>src/cbsa-core-service target/classes (Spring Boot 3.3.4, Java 21), default profile, '
         'jdbc:h2:mem per server; cbsa.clock.fixed=2026-01-15T10:30:00Z, cbsa.random.seed = transcript seed (one server per seed: 42, 7, 1234), '
         'cbsa.seed.location=baseline/seed/seed.sql. Before every flow the 4 tables are emptied, the seed reloaded and EIBTASKN reset '
         '(ParityServer seam). REST over HTTP on /webui-1.0.</td></tr>'
         '<tr><td>Migrated, PostgreSQL</td><td>Same classes and properties with <code>--spring.profiles.active=postgres</code> against PostgreSQL %s; '
         'each parity server gets its own schema (parity_parity42/7/1234, parity_screens, parity_parityui), dropped and re-created before start; '
         'Spring runs schema.sql and loads the same seed.</td></tr></table><br>'
         % (legacy['totals']['match'], legacy['totals']['diff'], legacy['totals']['fixture'], E(PGV or '(not run)')))
H.append('<table><tr><th>Program</th><th>Legacy execution</th><th>Migrated entry point used</th></tr>')
for prog, how, mig in [
        ('GETCOMPY, GETSCODE', 'GnuCOBOL + stubs, LINK/COMMAREA', 'GET /banking/companyName, /banking/sortCode'),
        ('CRECUST + CRDTAGY1-5', 'GnuCOBOL + stubs, LINK; agencies as deterministic child tasks (RUN TRANSID/FETCH ANY/containers)', 'POST /banking/customer'),
        ('INQCUST, UPDCUST, DELCUS', 'GnuCOBOL + stubs, LINK', 'GET/PUT/DELETE /banking/customer/{id}'),
        ('CREACC, INQACC, UPDACC, DELACC', 'GnuCOBOL + stubs, LINK', 'POST /banking/account, GET/PUT/DELETE /banking/account/{id}'),
        ('INQACCCU', 'GnuCOBOL + stubs, LINK', 'GET /banking/account/retrieveByCustomerNumber/{id}'),
        ('DBCRFUN', 'GnuCOBOL + stubs, LINK', 'PUT /makepayment/dbcr; PUT /banking/account/{debit|credit}/{id}'),
        ('XFRFUN', 'GnuCOBOL + stubs, LINK', 'PUT /banking/account/transfer/{id}'),
        ('ABNDPROC', 'GnuCOBOL + stubs, LINK (ABNDFILE trace)', 'POST /banking/admin/abend (property-gated) + GET /banking/admin/abend'),
        ('BANKDATA', 'GnuCOBOL + stubs, batch (generates the canonical seed)', 'seed equality check before every flow'),
        ('BNKMENU, BNK1CAC/CCA/CCS/CRA/DAC/DCS/TFN/UAC', 'GnuCOBOL + stubs, terminal mode, BMS maps as named fields', 'screens.* handlers in-process')]:
    H.append('<tr><td>%s</td><td>%s</td><td>%s</td></tr>' % (E(prog), E(how), E(mig)))
H.append('</table></section>')

# 3. tests
rb = react.get('build') or {}
H.append('<section><h2>3. Test suites (both sides)</h2><table><tr><th>Side</th><th>Suite</th><th>Result</th></tr>'
         '<tr><td>Legacy</td><td>legacy/run-all.sh (107 baseline transcripts)</td><td>%d match / %d diff / %d golden fixtures</td></tr>'
         '<tr><td>Legacy</td><td>other tests</td><td>none exist in the repository (the COBOL estate has no unit tests)</td></tr>'
         '<tr><td>Migrated</td><td>mvn -B verify (JDK 21)</td><td>%d tests in %d classes: %d failures, %d errors, %d skipped</td></tr>'
         % (legacy['totals']['match'], legacy['totals']['diff'], legacy['totals']['fixture'], mvn['totals']['tests'], mvn['classes'],
            mvn['totals']['failures'], mvn['totals']['errors'], mvn['totals']['skipped']))
if pgit:
    H.append('<tr><td>Migrated</td><td>PostgresProfileMainFlowsTest (opt-in, CBSA_PG_IT_URL, PostgreSQL %s)</td><td>%d tests: %d failures, %d errors, %d skipped (%s)</td></tr>'
             % (E(PGV), pgit['tests'], pgit['failures'], pgit['errors'], pgit['skipped'], E(', '.join(pgit['cases']))))
H.append('<tr><td>React</td><td><code>%s</code></td><td>exit %s &mdash; suites: %s; tests: %s</td></tr>'
         '<tr><td>React</td><td><code>%s</code></td><td>exit %s, bundle %s, served by cbsa-core-service static/: %s (identical: %s)</td></tr>'
         % (E(react.get('command')), E(react.get('exit_code')), E(react.get('suites')), E(react.get('tests')), E(rb.get('command')), E(rb.get('exit_code')),
            E(', '.join(rb.get('bundle') or [])), E(', '.join(rb.get('served_by_core_service') or [])), E(rb.get('bundle_matches_served'))))
for db in DBS:
    u = A[db]['react_ui_browser']
    H.append('<tr><td>React + migrated</td><td>UI in Chromium against migrated service on %s (section 6)</td><td>%d of %d identical to legacy data</td></tr>'
             % (RUNS[db]['label'], u['matching'], u['checks']))
H.append('</table><p><small>React test files: %s</small></p><details><summary>mvn counts per class</summary><table><tr><th>Class</th><th>Tests</th><th>Fail</th><th>Err</th><th>Skip</th></tr>'
         % E(', '.join(react.get('test_files') or [])))
for c in mvn['per_class']:
    H.append('<tr><td>%s</td><td>%d</td><td>%d</td><td>%d</td><td>%d</td></tr>' % (E(c['class']), c['tests'], c['failures'], c['errors'], c['skipped']))
H.append('</table></details></section>')

# 4. verified parity
H.append('<section class="ver"><h2>4. VERIFIED PARITY</h2><p>Observed in this run, per database: outcome, every compared response field, '
         'DB delta and full table dumps identical (only the documented normalisation applied).</p><table><tr><th>Kind</th>' +
         ''.join('<th>%s MATCH</th><th>%s DIFF</th><th>%s ERROR</th><th>%s NOT_COMPARABLE</th>' % ((RUNS[d]['label'],) * 4) for d in DBS) + '</tr>')
for k in A['h2']['by_kind']:
    H.append('<tr><td>%s</td>%s</tr>' % (E(k), ''.join('<td>%d</td><td>%d</td><td>%d</td><td>%d</td>' % tuple(
        A[d]['by_kind'].get(k, {}).get(s, 0) for s in ('MATCH', 'DIFF', 'ERROR', 'NOT_COMPARABLE')) for d in DBS)))
H.append('<tr><th>Total</th>%s</tr></table>' % ''.join('<th>%d</th><th>%d</th><th>%d</th><th>%d</th>' % (
    A[d]['flows_matching'], A[d]['flows_comparable'] - A[d]['flows_matching'], len(A[d]['execution_errors']), A[d]['flows_not_comparable']) for d in DBS))
H.append('<p>Includes: credit-check scores and review dates (seeds 42, 7, 1234/taskn 500), CREACC opening balances for ISA/LOAN/MORTGAGE/SAVING, '
         'UPDACC non-blank type rule, INQCUST random customer, PROCTRAN_REF on every PROCTRAN row, ABNDPROC response + ABNDFILE contents, all DBCRFUN '
         'and XFRFUN paths (incl. ABEND SAME, rollback fail code 2), DELCUS cascades, CREACC 9th/10th account rule, CONTROL counters, all BNKMENU '
         'routing and every 3270 screen field by field &mdash; on each database where the table above shows MATCH.</p></section>')

# 5. migration defects
H.append('<section class="def"><h2>5. MIGRATION DEFECTS</h2>')
for k, d in ALL_DEFECTS.items():
    H.append('<h3>%s &mdash; %s</h3><table>' % (E(k), E(d['title'])) + ''.join('<tr><th>%s</th><td>%s</td></tr>' % (E(a), E(d[b])) for a, b in
             [('Flow', 'flow'), ('Field', 'field'), ('Legacy', 'legacy'), ('Migrated', 'migrated')]) + '</table>')
errs = [(db, e) for db in DBS for e in A[db]['execution_errors']]
H.append('<p>%s</p>' % ('No migration defect observed on %s.' % ' or '.join(RUNS[d]['label'] for d in DBS) if not ALL_DEFECTS else
                        'Every difference above is listed precisely; Java code was not changed by this parity task.'))
if errs:
    H.append('<h3>Execution errors (flow not compared &mdash; neither parity nor defect)</h3><table><tr><th>DB</th><th>Flow</th><th>Error</th></tr>' +
             ''.join('<tr><td>%s</td><td>%s</td><td><code>%s</code></td></tr>' % (E(RUNS[db]['label']), E(e['id']), E(e['error'])) for db, e in errs) + '</table>')
H.append('<h3>Harness findings in this round (parity/** only)</h3><ul>' + ''.join('<li>%s</li>' % E(x) for x in HARNESS_NOTES) + '</ul></section>')

# 6. React UI
for db in DBS:
    ui = RUNS[db]['ui']
    u = A[db]['react_ui_browser']
    H.append('<section class="%s"><h2>6%s. React UI end-to-end in Chromium &mdash; migrated service on %s</h2><p>%s driven with %s. Expected '
             'values come from the legacy harness&#39;s seeded SQLite database, formatted the way the React row builders format them.</p>'
             '<table><tr><th>Check</th><th>Status</th><th>Field</th><th>Legacy (seeded DB)</th><th>Migrated (rendered in UI)</th><th>Equal</th></tr>' % (
                 'ver' if u['checks'] and u['matching'] == u['checks'] else 'def', 'a' if db == 'h2' else 'b', RUNS[db]['label'], E(ui.get('base', '')), E(ui.get('browser', ''))))
    for r in ui['results']:
        fl = r.get('fields') or [{'field': r.get('error', ''), 'legacy': '', 'migrated_ui': '', 'equal': False}]
        for i, f in enumerate(fl):
            H.append('<tr>%s<td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>' % (
                ('<td rowspan="%d">%s<br><small>%s</small></td><td rowspan="%d" class="%s">%s</td>' % (
                    len(fl), E(r['id']), E(r['title']), len(fl), r['status'], r['status'])) if i == 0 else '',
                E(f['field']), J(f['legacy']), J(f['migrated_ui']), 'yes' if f['equal'] else '<b class="DIFF">no</b>'))
    H.append('</table><details><summary>REST calls the UI made</summary><pre>%s</pre></details>' % E('\n'.join(
        '%s  %s %s' % (r['id'], c['status'], c['url']) for r in ui['results'] for c in r.get('api_calls', []))))
    for sh in (ui['screenshots'] if db == 'h2' else ui['screenshots'][2:3]):
        H.append('<h3>%s (%s)</h3><img alt="%s" src="data:image/jpeg;base64,%s">' % (E(sh['name']), RUNS[db]['label'], E(sh['name']), sh['jpeg_b64']))
    H.append('</section>')

# 7. known limitations
H.append('<section class="lim"><h2>7. KNOWN LIMITATIONS (coverage boundaries; no flow difference is attributed to them)</h2><ul>' +
         ''.join('<li>%s</li>' % E(x) for x in LIMITATIONS) + '</ul></section>')

# 8. legacy defects
H.append('<section class="leg"><h2>8. PRE-EXISTING LEGACY DEFECTS (baseline/README.md; preserved, not fixed)</h2><table><tr><th>Ref</th><th>Defect</th>'
         '<th>Flows exercising it</th><th>Parity status</th></tr>')
for d in LEGACY_DEFECTS:
    H.append('<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>' % (E(d['ref']), E(d['text']), E(', '.join(d['flows']) or '-'), E(d['parity'])))
H.append('</table></section>')

# 9. every difference
H.append('<section><h2>9. Every difference observed in round 3 (field by field)</h2>')
rows = [(db, r, d) for db in DBS for r in RUNS[db]['flows'] for d in r['diffs']]
if rows:
    H.append('<table><tr><th>DB</th><th>Flow</th><th>Step</th><th>Area</th><th>Field</th><th>Legacy</th><th>Migrated</th></tr>' + ''.join(
        '<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td><code>%s</code></td><td><code>%s</code></td></tr>' % (
            E(RUNS[db]['label']), E(r['id']), E(d.get('step', 1)), E(d['area']), E(d['field']), J(d['legacy'])[:400], J(d['migrated'])[:400])
        for db, r, d in rows) + '</table>')
else:
    H.append('<p>None: 0 field differences on %s. Differences observed in earlier rounds, with root cause and resolution, are in section 0; '
             'the round-3 harness findings are in section 5.</p>' % ' and '.join(RUNS[d]['label'] for d in DBS))
H.append('</section>')


# 10. per-flow
def oc(o):
    return '' if not o else o['kind'] + ((' ' + str(o['code'])) if o.get('code') else '')


def outcome(r, side):
    if not r or not r['steps']:
        return E(r.get('error', '')) if r else ''
    st = r['steps'][-1]
    if r['kind'] == 'transcript-terminal':
        s0 = r['steps'][0]
        if side == 'legacy':
            return 'screen' + (' ABEND ' + s0['legacy_abend']['code'] if s0.get('legacy_abend') else '')
        return 'screen' + (' ' + E(s0['migrated_error']) if s0.get('migrated_error') else '')
    if r['kind'] == 'api-crosscheck':
        return J(st.get('%s_value' % side))[:80]
    return ' &rarr; '.join(E(oc(s.get('%s_outcome' % side))) for s in r['steps'])


def tables(r):
    tb = (r['steps'][-1].get('tables_after') if r and r['steps'] else None) or {}
    return ' / '.join('%s|%s' % (v['rows_legacy'], v['rows_migrated']) + ('' if v['equal'] else '!') for v in tb.values())


FL = {db: {r['id']: r for r in RUNS[db]['flows']} for db in DBS}
H.append('<section><h2>10. Per-flow results: legacy vs migrated on %s</h2><input id="q" placeholder="filter flows (e.g. CREACC, DIFF)" '
         'oninput="var q=this.value.toLowerCase();document.querySelectorAll(\'#pf tr.r\').forEach(function(t){t.style.display=t.textContent.toLowerCase().indexOf(q)<0?\'none\':\'\'})">'
         '<table id="pf"><tr><th>#</th><th>Flow</th><th>Kind</th><th>Program</th><th>Legacy outcome</th>' % ' and '.join(RUNS[d]['label'] for d in DBS) +
         ''.join('<th>%s outcome</th><th>%s tables after C/A/P/Ctl rows L|M</th><th>%s status</th>' % ((RUNS[d]['label'],) * 3) for d in DBS) + '</tr>')
for i, r in enumerate(RUNS['h2']['flows'], 1):
    cells = ''
    for d in DBS:
        x = FL[d].get(r['id'])
        s = x['status'] if x else 'absent'
        cells += '<td>%s</td><td>%s</td><td class="%s">%s%s</td>' % (outcome(x, 'migrated'), E(tables(x)), s, s, (' (%d diffs)' % len(x['diffs'])) if x and x['diffs'] else '')
    H.append('<tr class="r"><td>%d</td><td>%s<br><small>%s</small></td><td>%s</td><td>%s</td><td>%s</td>%s</tr>' % (
        i, E(r['id']), E(r.get('title', ''))[:120], E(r['kind']), E(r['program']), outcome(r, 'legacy'), cells))
H.append('</table></section>')

# 11. evidence
H.append('<section><h2>11. Evidence: terminal captures (embedded PNG)</h2>')
for title, a, b in images:
    H.append('<h3>%s</h3><div class="pair"><img alt="legacy %s" src="%s"><img alt="migrated %s" src="%s"></div>' % (E(title), E(title), a, E(title), b))
for db, img in consoles:
    H.append('<h3>Parity run console (%s)</h3><img alt="console %s" src="%s">' % (RUNS[db]['label'], db, img))
H.append('</section><section><h2>12. Reproduce</h2><pre>docker run -d -e POSTGRES_DB=cbsa -e POSTGRES_USER=cbsa -e POSTGRES_PASSWORD=cbsa -p 5432:5432 postgres:16\n'
         'parity/run-parity.sh            # legacy build + run-all, mvn verify, React tests + build, parity on H2 and PostgreSQL, PG IT, report\n'
         'SKIP_MVN=1 parity/run-parity.sh # reuse an existing mvn verify\n'
         'results: parity/results/{flows,flows-postgres,react-ui,react-ui-postgres,legacy-replay-all,tests-migrated,tests-postgres-it,tests-react,summary}.json\n'
         'history: parity/results/round{1,2,3}/</pre></section></main></body></html>')
out = os.path.join(PARITY, 'report', 'parity-report.html')
open(out, 'w').write('\n'.join(H))
print('wrote', out, os.path.getsize(out), 'bytes;', {db: '%d/%d' % (A[db]['flows_matching'], A[db]['flows_total']) for db in DBS}, 'defects:', sorted(ALL_DEFECTS))
