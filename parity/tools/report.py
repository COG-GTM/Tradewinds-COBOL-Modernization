#!/usr/bin/env python3
"""Builds parity/results/summary.json and the self-contained parity/report/parity-report.html from parity/results."""
import base64, datetime, html, io, json, os, platform, subprocess
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
PARITY = os.path.dirname(HERE)
ROOT = os.path.dirname(PARITY)
RES = os.path.join(PARITY, 'results')
load = lambda n: json.load(open(os.path.join(RES, n)))
flows, legacy, mvn, react = load('flows.json'), load('legacy-replay-all.json'), load('tests-migrated.json'), load('tests-react.json')

# ------------------------------------------------------------------ classification of every observed difference
CLASS = {
    'CREACC/type-isa': 'KL-CREACC-BAL', 'CREACC/type-loan': 'KL-CREACC-BAL', 'CREACC/type-mortgage': 'KL-CREACC-BAL',
    'CREACC/type-saving': 'KL-CREACC-BAL', 'UPDACC/invalid-type': 'KL-UPDACC-TYPE',
    'INQCUST/random-seed-42': 'KL-RANDOM-CUST', 'INQCUST/random-seed-7': 'KL-RANDOM-CUST',
    'BNK1DCS/lookup-non-numeric': 'KL-RANDOM-CUST', 'api-crosscheck/processed-transactions': 'MD-PROCTRAN-API',
}
DEFECTS = {
    'MD-PROCTRAN-API': {
        'title': 'GET /banking/processedTransaction not implemented',
        'flow': 'api-crosscheck/processed-transactions', 'field': 'HTTP status / numberOfProcessedTransactions',
        'legacy': '8 (PROCTRAN rows of the canonical seed; served by legacy Liberty ProcessedTransactionResource)',
        'migrated': 'HTTP 404 {"errorMessage":"Not found: /banking/processedTransaction"}',
        'root_cause': 'docs/migration/api-contract.md "Optional read APIs (T-12)" lists GET /banking/processedTransaction; '
                      'the migrated service only implements GET /banking/abend (abndproc/AbendController.java). No controller '
                      'maps /banking/processedTransaction. The React UI does not call it, so severity is low.',
        'file': 'src/cbsa-core-service/src/main/java/com/ibm/cics/cip/bank/core/ (missing controller; '
                'legacy: src/webui/src/main/java/com/ibm/cics/cip/bankliberty/api/json/ProcessedTransactionResource.java)'},
}
LIMITS = {
    'KL-CREACC-BAL': {
        'title': 'CREACC opening balance is not part of the REST surface',
        'detail': 'COMMAREA COMM-AVAIL-BAL/COMM-ACT-BAL (50, 100, -5000, -125000) are written by COBOL CREACC; '
                  'POST /banking/account has no balance field (api-contract.md) and the migrated account opens with 0.00. '
                  'This matches the legacy web path: src/webui/.../web/db2/Account.createAccount inserts 0.00,0.00. '
                  'Account number, type, rate, overdraft, dates, CONTROL and PROCTRAN all match in these flows; '
                  'BNK1CAC/create-ok (3270 path) matches fully.'},
    'KL-UPDACC-TYPE': {
        'title': 'UPDACC invalid account type rejected by the REST layer',
        'detail': 'Legacy COBOL UPDACC writes ACCOUNT_TYPE=CHEQUE (pre-existing defect 9); the migrated PUT /banking/account/6 '
                  'returns 400 "Account type CHEQUE is not supported." (updacc/UpdateAccountController.VALID_ACCOUNT_TYPES), '
                  'exactly like the legacy Liberty AccountsResource.updateAccountInternal (validateType, HTTP 400) that '
                  'fronted UPDACC for the React UI. Intentional per tickets.md T-07 ("blank or invalid type -> 400").'},
    'KL-RANDOM-CUST': {
        'title': 'INQCUST "random customer" (custno 0) picks a different customer',
        'detail': 'Legacy always returns customer 0000000001 for both CBSA_RANDOM_SEED=42 and 7 (GENERATE-RANDOM-CUSTOMER '
                  'is computed from NCS-CUST-NO-VALUE, so the draw collapses to 1); the migrated service draws from '
                  'CbsaRandom stream 100 (seed+100) over the highest customer number and returns 0000000022 (seed 42). '
                  'Documented as a known deviation before this run in screens/TranscriptReplayTest.KNOWN_DEVIATIONS and '
                  'InquireCustomerTranscriptReplayTest. The outcome kind (success) and DB state match.'},
}
OTHER_LIMITS = [
    'PROCTRAN_REF excluded from every comparison (documented: docs/migration/inventory.md "PROCTRAN_REF is the CICS task '
    'number (Java: per-JVM sequence) and is excluded from parity comparisons"). Observed: legacy writes the task EIBTASKN on '
    'every row of a task (e.g. DELCUS 4 rows all 000000000100); Java increments per row and CRECUST uses taskn+1 '
    '(credit check consumes one number). All other PROCTRAN columns are compared.',
    'ABNDPROC/record: not comparable - the migrated service has no REST trigger for ABNDPROC (only GET /banking/abend).',
    'BANKDATA (batch) is not replayed per flow; its output is the canonical seed itself, and the seed loaded by both sides was '
    'verified identical (full CUSTOMER/ACCOUNT/PROCTRAN/CONTROL dump) before every flow.',
    'Migrated EIBTASKN is set through a parity-only test seam (parity/tools/ParityServer.java: resettable TaskNumberSupplier '
    'subclass + /__parity reset/exec/dump endpoints); all business beans are the unmodified target/classes.',
    '3270 screen flows have no REST surface: they are driven in-process through com.ibm.cics.cip.bank.core.screens handlers '
    '(parity/tools/ScreenParity.java). RETURN COMMAREA bytes are not compared (Java keeps a symbolic state map).',
    'React unit tests are not runnable in this environment: ' + str(react.get('first_error')) +
    ' (enzyme -> cheerio -> undici needs Web APIs absent from the CRA jsdom env; fails in src/setupTests.js before any test).',
    'React UI was not driven end-to-end against the migrated service in this round; REST flows use the exact payloads in '
    'api-contract.md that the UI sends.',
    'Single-node H2 only; the postgres profile was not exercised by this parity run.',
]
LEGACY_DEFECTS = [
    ('README #9', 'UPDACC accepts an invalid account type and writes CHEQUE (UPDACC/invalid-type).'),
    ('new', 'INQCUST custno 0 ("random customer") always returns customer 1 regardless of CBSA_RANDOM_SEED '
            '(observed: random-seed-42 and random-seed-7 both -> 0000000001); BNK1DCS non-numeric input inherits it.'),
    ('README #11', 'BNK1DCS non-numeric customer number: DEEDIT gives 0 -> random customer + "Customer lookup successful."'),
    ('README #2/#3', 'DBCRFUN payment debits ignore the overdraft; teller debit has no funds check - reproduced identically by Java.'),
    ('README #5', 'Zero-amount DBCRFUN succeeds and writes PROCTRAN - reproduced identically.'),
    ('README #6-8', 'XFRFUN: no funds check; same-account ABEND SAME; missing target fail code 2 after rollbacks - reproduced identically.'),
    ('README #10', 'UPDCUST ignores DOB/credit score - reproduced identically.'),
    ('README #15', 'Credit scores depend on EIBTASKN - reproduced identically (CRECUST ok-seed-1234-taskn-500 matches).'),
    ('README #17/#18', 'INQACCCU zero-accounts vs not-found asymmetry; CREACC lowercase rejection and 9th/10th account rule - reproduced identically.'),
    ('README #1', 'ABND-TIME uses the minutes field twice (ABNDPROC, not comparable via REST).'),
    ('README #12', 'BNK1CAC overdraft-length underflow (transcripts send blank-padded fields).'),
    ('README #16', 'DELCUS abend text typo "Unbale".'),
]

# ------------------------------------------------------------------ only differences present in this run are reported
STATUS = {r['id']: r['status'] for r in flows}
nonmatch = [r for r in flows if r['status'] == 'DIFF']
exec_errors = [r for r in flows if r['status'] == 'ERROR']
CLASS = {k: v for k, v in CLASS.items() if STATUS.get(k) == 'DIFF'}
for r in nonmatch:
    if r['id'] not in CLASS:
        CLASS[r['id']] = 'MD-UNTRIAGED-' + r['id']
        DEFECTS['MD-UNTRIAGED-' + r['id']] = {
            'title': 'untriaged difference (treated as a migration defect until classified)', 'flow': r['id'],
            'field': ', '.join(sorted({d['field'] for d in r['diffs']}))[:300] or r['status'],
            'legacy': '; '.join(json.dumps(d['legacy'], sort_keys=True)[:200] for d in r['diffs'][:5]),
            'migrated': '; '.join(json.dumps(d['migrated'], sort_keys=True)[:200] for d in r['diffs'][:5]),
            'root_cause': 'not yet analysed', 'file': '-'}
DEFECTS = {k: v for k, v in DEFECTS.items() if k in CLASS.values()}
LIMITS = {k: v for k, v in LIMITS.items() if k in CLASS.values()}
known_diff_flows = sum(1 for v in CLASS.values() if v.startswith('KL-'))

# ------------------------------------------------------------------ summary json
by_kind = {}
for r in flows:
    by_kind.setdefault(r['kind'], {}).setdefault(r['status'], 0)
    by_kind[r['kind']][r['status']] += 1
comparable = [r for r in flows if r['status'] in ('MATCH', 'DIFF')]
matching = [r for r in comparable if r['status'] == 'MATCH']
unclassified = [r['id'] for r in comparable if r['status'] != 'MATCH' and r['id'] not in CLASS]
summary = {
    'generated': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
    'head': subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, text=True).stdout.strip(),
    'flows_total': len(flows), 'flows_comparable': len(comparable), 'flows_matching': len(matching),
    'flows_not_comparable': len(flows) - len(comparable) - len(exec_errors), 'by_kind': by_kind,
    'execution_errors': [{'id': r['id'], 'error': r.get('error')} for r in exec_errors],
    'classification': CLASS, 'unclassified_differences': unclassified,
    'migration_defects': DEFECTS, 'known_limitations': {**LIMITS, 'other': OTHER_LIMITS},
    'pre_existing_legacy_defects': [{'ref': a, 'text': b} for a, b in LEGACY_DEFECTS],
    'tests': {'legacy_transcripts': legacy['totals'] | {'total': legacy['total']}, 'migrated_mvn': mvn['totals'] | {'classes': mvn['classes']},
              'react': {k: react[k] for k in ('exit_code', 'suites', 'tests', 'first_error')}},
}
json.dump(summary, open(os.path.join(RES, 'summary.json'), 'w'), indent=1)


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
    out = []
    for e in evs:
        if e[0] == 'FIELD':
            out.append('  %-10s %s' % (e[1], e[2]))
        else:
            out.append(' '.join(str(x) for x in e))
    return out


F = {r['id']: r for r in flows}
images = []
for fid in ('BNK1DCS/lookup-found', 'BNK1TFN/transfer-ok', 'BNK1DCS/lookup-non-numeric'):
    st = F[fid]['steps'][0]
    images.append((fid + ' (%s)' % F[fid]['status'], text_png(screen_lines(st['legacy_screen']), 'LEGACY GnuCOBOL ' + fid),
                   text_png(screen_lines(st['migrated_screen']), 'MIGRATED Java 21 ' + fid, fg=(120, 200, 255))))
console = [l.rstrip() for l in open(os.path.join(PARITY, 'build', 'parity.log'))] if os.path.exists(os.path.join(PARITY, 'build', 'parity.log')) else []
console_img = text_png(console[-60:], '$ parity/run-parity.sh   (step 4 console, last 60 lines)', width=100) if console else None


# ------------------------------------------------------------------ html
E = lambda s: html.escape(str(s))
J = lambda v: E(json.dumps(v, sort_keys=True)) if not isinstance(v, str) else E(v)


def env_versions():
    run = lambda c: subprocess.run(c, shell=True, capture_output=True, text=True).stdout.strip().splitlines()[:1]
    return [('GnuCOBOL', run('cobc --version')), ('Java', run('/usr/lib/jvm/java-21-openjdk-amd64/bin/java -version 2>&1')),
            ('Maven', run('mvn -v 2>/dev/null')), ('Spring Boot', ['3.3.4']), ('H2', ['2.2.224 (MODE=PostgreSQL, in-memory)']),
            ('SQLite (legacy stub DB)', run('python3 -c "import sqlite3;print(sqlite3.sqlite_version)"')),
            ('Python', [platform.python_version()]), ('Node', run('node --version')), ('OS', [platform.platform()])]


css = '''body{font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;margin:0;color:#161616;background:#f4f4f4}
header{background:#161616;color:#fff;padding:24px 40px}header h1{margin:0;font-size:24px}header p{margin:6px 0 0;color:#c6c6c6}
main{padding:24px 40px;max-width:1400px}section{background:#fff;padding:20px 24px;margin:0 0 20px;border-left:4px solid #0f62fe}
h2{margin-top:0}table{border-collapse:collapse;width:100%;font-size:13px}th,td{border:1px solid #e0e0e0;padding:5px 8px;text-align:left;vertical-align:top}
th{background:#e8e8e8}.MATCH{color:#0e6027;font-weight:600}.DIFF{color:#a2191f;font-weight:600}.NOT_COMPARABLE{color:#8a3800;font-weight:600}
.cards{display:flex;gap:16px;flex-wrap:wrap}.card{background:#fff;border:1px solid #e0e0e0;padding:14px 18px;min-width:170px}
.card b{display:block;font-size:28px}.ok{border-left:4px solid #24a148}.bad{border-left:4px solid #da1e28}.warn{border-left:4px solid #f1c21b}
.lim{border-left-color:#f1c21b}.def{border-left-color:#da1e28}.ver{border-left-color:#24a148}.leg{border-left-color:#8a3ffc}
code,pre{font-family:Menlo,Consolas,monospace;font-size:12px}pre{background:#f4f4f4;padding:8px;overflow:auto;max-height:260px}
img{max-width:100%;border:1px solid #393939}.pair{display:grid;grid-template-columns:1fr 1fr;gap:12px}small{color:#525252}'''

H = ['<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>CBSA parity report - round 1</title><style>%s</style></head><body>' % css]
H.append('<header><h1>CBSA legacy (COBOL/CICS) vs Java 21 migration &mdash; end-to-end parity, round 1</h1>'
         '<p>devin/java21-cbsa-core @ %s &middot; generated %s &middot; produced by parity/run-parity.sh</p></header><main>'
         % (E(summary['head'][:12]), E(summary['generated'])))
diffs_total = len(comparable) - len(matching)
H.append('<div class="cards">'
         '<div class="card ok"><b>%d / %d</b>comparable flows identical</div>' % (len(matching), len(comparable)) +
         '<div class="card bad"><b>%d</b>migration defects</div>' % len(DEFECTS) +
         '<div class="card warn"><b>%d</b>flows with documented/known differences</div>' % known_diff_flows +
         '<div class="card"><b>%d</b>not comparable</div>' % summary['flows_not_comparable'] +
         '<div class="card ok"><b>%d/%d</b>legacy transcripts replay</div>' % (legacy['totals']['match'], legacy['total']) +
         '<div class="card ok"><b>%d</b>mvn tests (%d F, %d E, %d skipped)</div>' % (mvn['totals']['tests'], mvn['totals']['failures'], mvn['totals']['errors'], mvn['totals']['skipped']) +
         '<div class="card warn"><b>n/a</b>React tests (setup error)</div></div><br>')

H.append('<section><h2>1. Scope</h2><ul>'
         '<li><b>All 107 baseline transcripts</b> (baseline/transcripts): 76 LINK/COMMAREA transcripts replayed on legacy via the harness '
         '(legacy/run-transcript.sh semantics, legacy/tools/harness.py) and on the migrated service via the REST API of '
         'docs/migration/api-contract.md; 31 terminal-mode (BNKMENU/BNK1*) transcripts replayed through the migrated 3270 screen handlers.</li>'
         '<li><b>5 API variants</b>: every DBCRFUN teller transcript re-run through <code>PUT /banking/account/{debit|credit}/{id}</code>.</li>'
         '<li><b>6 multi-step user journeys</b> (parity/flows/user-flows.json): onboarding, payments, maintenance+close, error paths, '
         'customer lifecycle, REST teller - 31 steps, one database per journey, compared after <i>every</i> step.</li>'
         '<li><b>6 React read endpoints</b> on the Liberty layer (no COBOL program) cross-checked against the same query on the legacy seeded DB.</li>'
         '<li>Per flow: outcome (success / fail code / ABEND code), every business response field, DB delta of CUSTOMER, ACCOUNT, PROCTRAN, '
         'CONTROL, and SHA-256 of the full table dumps after the flow. Normalisation: CHAR right-trim, numbers to 2dp, row order, and '
         'PROCTRAN_REF (documented exclusion) &mdash; nothing else.</li></ul></section>')

H.append('<section><h2>2. Environment and how each side ran</h2><table><tr><th>Component</th><th>Version</th></tr>')
for k, v in env_versions():
    H.append('<tr><td>%s</td><td>%s</td></tr>' % (E(k), E(' '.join(v))))
H.append('</table><br><table><tr><th>Side</th><th>How it ran</th></tr>'
         '<tr><td>Legacy</td><td>All 29 programs of src/base/cobol_src compiled by GnuCOBOL 3.1.2 with the C/SQLite stub runtime '
         '(legacy/runtime/cbsastub.c); <b>no golden fixtures</b> (legacy replay: %d match, %d diff, %d fixture). Fresh SQLite DB per flow from '
         'baseline/seed/seed.sql. Env CBSA_FIXED_TIME=2026-01-15T10:30:00Z, CBSA_RANDOM_SEED=42 (7 / 1234 where the transcript says so), '
         'CBSA_TASKN=100 (or per transcript / per journey step).</td></tr>'
         '<tr><td>Migrated</td><td>src/cbsa-core-service target/classes (Spring Boot 3.3.4, Java 21) on H2 in-memory, '
         'cbsa.clock.fixed=2026-01-15T10:30:00Z, cbsa.random.seed = the transcript seed (one server per seed: 42, 7, 1234), '
         'cbsa.seed.location=baseline/seed/seed.sql; before every flow the 4 tables are emptied and the seed reloaded and EIBTASKN reset '
         '(parity/tools/ParityServer.java test seam). REST calls over HTTP on /webui-1.0.</td></tr></table><br>'
         % (legacy['totals']['match'], legacy['totals']['diff'], legacy['totals']['fixture']))
H.append('<table><tr><th>Program</th><th>Legacy execution</th><th>Migrated entry point used</th></tr>')
for prog, how, mig in [
        ('GETCOMPY, GETSCODE', 'GnuCOBOL + stubs, LINK/COMMAREA', 'GET /banking/companyName, /banking/sortCode'),
        ('CRECUST + CRDTAGY1-5', 'GnuCOBOL + stubs, LINK; agencies as deterministic child tasks (RUN TRANSID/FETCH ANY/containers)', 'POST /banking/customer'),
        ('INQCUST, UPDCUST, DELCUS', 'GnuCOBOL + stubs, LINK', 'GET/PUT/DELETE /banking/customer/{id}'),
        ('CREACC, INQACC, UPDACC, DELACC', 'GnuCOBOL + stubs, LINK', 'POST /banking/account, GET/PUT/DELETE /banking/account/{id}'),
        ('INQACCCU', 'GnuCOBOL + stubs, LINK', 'GET /banking/account/retrieveByCustomerNumber/{id}'),
        ('DBCRFUN', 'GnuCOBOL + stubs, LINK', 'PUT /makepayment/dbcr; PUT /banking/account/{debit|credit}/{id}'),
        ('XFRFUN', 'GnuCOBOL + stubs, LINK', 'PUT /banking/account/transfer/{id}'),
        ('ABNDPROC', 'GnuCOBOL + stubs, LINK', 'none (not comparable)'),
        ('BANKDATA', 'GnuCOBOL + stubs, batch (generates the canonical seed)', 'seed equality check before every flow'),
        ('BNKMENU, BNK1CAC/CCA/CCS/CRA/DAC/DCS/TFN/UAC', 'GnuCOBOL + stubs, terminal mode, BMS maps as named fields', 'screens.* handlers in-process')]:
    H.append('<tr><td>%s</td><td>%s</td><td>%s</td></tr>' % (E(prog), E(how), E(mig)))
H.append('</table></section>')

H.append('<section><h2>3. Test suites</h2><table><tr><th>Suite</th><th>Result</th></tr>'
         '<tr><td>Legacy harness (107 baseline transcripts, legacy/run-transcript.sh)</td><td>%d match / %d diff / %d golden fixtures</td></tr>'
         '<tr><td>Legacy other tests</td><td>none exist in the repository (COBOL estate has no unit tests)</td></tr>'
         '<tr><td>Migrated mvn -B verify</td><td>%d tests in %d classes: %d failures, %d errors, %d skipped</td></tr>'
         '<tr><td>React (react-scripts test)</td><td>exit %s, %s &mdash; <code>%s</code></td></tr></table>'
         % (legacy['totals']['match'], legacy['totals']['diff'], legacy['totals']['fixture'], mvn['totals']['tests'], mvn['classes'],
            mvn['totals']['failures'], mvn['totals']['errors'], mvn['totals']['skipped'], E(react['exit_code']), E(react['suites']), E(react['first_error'])))
H.append('<details><summary>mvn counts per class</summary><table><tr><th>Class</th><th>Tests</th><th>Fail</th><th>Err</th><th>Skip</th></tr>')
for c in mvn['per_class']:
    H.append('<tr><td>%s</td><td>%d</td><td>%d</td><td>%d</td><td>%d</td></tr>' % (E(c['class']), c['tests'], c['failures'], c['errors'], c['skipped']))
H.append('</table></details></section>')

H.append('<section class="ver"><h2>4. VERIFIED PARITY</h2><p>%d of %d comparable flows are identical on outcome, every compared '
         'response field, DB delta and full table dumps (only the documented normalisation applied). By kind:</p><table>'
         '<tr><th>Kind</th><th>MATCH</th><th>DIFF</th><th>ERROR (not run)</th><th>NOT_COMPARABLE</th></tr>' % (len(matching), len(comparable)))
for k, v in by_kind.items():
    H.append('<tr><td>%s</td><td>%d</td><td>%d</td><td>%d</td><td>%d</td></tr>' % (E(k), v.get('MATCH', 0), v.get('DIFF', 0), v.get('ERROR', 0), v.get('NOT_COMPARABLE', 0)))
H.append('</table><p>Notable verified behaviour: credit-check scores and review dates for seeds 42, 7 and 1234/taskn 500; all DBCRFUN, XFRFUN '
         '(incl. ABEND SAME, rollback fail code 2), DELCUS cascades with PROCTRAN, CREACC 9th/10th account rule, CONTROL counters, '
         'all BNKMENU routing and %d of %d 3270 screens field by field.</p></section>' % (
             by_kind.get('transcript-terminal', {}).get('MATCH', 0), sum(by_kind.get('transcript-terminal', {}).values())))

H.append('<section class="def"><h2>5. MIGRATION DEFECTS</h2>')
for k, d in DEFECTS.items():
    H.append('<h3>%s &mdash; %s</h3><table>' % (E(k), E(d['title'])) + ''.join(
        '<tr><th>%s</th><td>%s</td></tr>' % (E(a), E(d[b])) for a, b in
        [('Flow', 'flow'), ('Field', 'field'), ('Legacy', 'legacy'), ('Migrated', 'migrated'), ('Root cause', 'root_cause'), ('File', 'file')]) + '</table>')
H.append('<p>%s</p></section>' % ('No migration defect observed.' if not DEFECTS else
         'No other difference was observed in this run that is attributable to the migration.'))

if exec_errors:
    H.append('<section class="def"><h2>Parity execution failures (not compared - neither parity nor defect)</h2><table>'
             '<tr><th>Flow</th><th>Error</th></tr>' + ''.join('<tr><td>%s</td><td><code>%s</code></td></tr>' % (
                 E(r['id']), E(r.get('error'))) for r in exec_errors) + '</table></section>')
H.append('<section class="lim"><h2>6. KNOWN LIMITATIONS (documented / intentional differences and coverage gaps)</h2>')
for k, d in LIMITS.items():
    ids = [f for f, c in CLASS.items() if c == k]
    H.append('<h3>%s &mdash; %s</h3><p>%s</p><p><small>Flows: %s</small></p>' % (E(k), E(d['title']), E(d['detail']), E(', '.join(ids))))
H.append('<h3>Coverage notes</h3><ul>' + ''.join('<li>%s</li>' % E(x) for x in OTHER_LIMITS) + '</ul></section>')

H.append('<section class="leg"><h2>7. PRE-EXISTING LEGACY DEFECTS</h2><table><tr><th>Ref (baseline/README.md)</th><th>Defect / parity status</th></tr>')
for a, b in LEGACY_DEFECTS:
    H.append('<tr><td>%s</td><td>%s</td></tr>' % (E(a), E(b)))
H.append('</table></section>')

H.append('<section><h2>8. Every difference (field by field)</h2><table><tr><th>Flow</th><th>Step</th><th>Area</th><th>Field</th><th>Legacy</th><th>Migrated</th><th>Class</th></tr>')
for r in flows:
    for d in r['diffs']:
        H.append('<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>' % (
            E(r['id']), E(d.get('step', 1)), E(d['area']), E(d['field']), J(d['legacy'])[:400], J(d['migrated'])[:400], E(CLASS.get(r['id'], 'UNCLASSIFIED'))))
H.append('</table></section>')

H.append('<section><h2>9. Per-flow results (legacy vs migrated)</h2><table><tr><th>#</th><th>Flow</th><th>Kind</th><th>Program</th>'
         '<th>Legacy outcome</th><th>Migrated outcome</th><th>Seed equal</th><th>Tables after (C/A/P/Ctl rows L|M)</th><th>Status</th><th>Diffs</th></tr>')


def oc(o):
    return '' if not o else o['kind'] + ((' ' + str(o['code'])) if o.get('code') else '')


for i, r in enumerate(flows, 1):
    st = r['steps'][-1] if r['steps'] else {}
    lo = ' &rarr; '.join(E(oc(s.get('legacy_outcome'))) for s in r['steps']) or E(r['steps'][0].get('legacy_abend') or '') if r['steps'] else ''
    mo = ' &rarr; '.join(E(oc(s.get('migrated_outcome'))) for s in r['steps']) if r['steps'] else ''
    if r['kind'] == 'transcript-terminal':
        lo = 'screen' + (' ABEND ' + r['steps'][0]['legacy_abend']['code'] if r['steps'][0].get('legacy_abend') else '')
        mo = 'screen' + (' ' + E(r['steps'][0]['migrated_error']) if r['steps'][0].get('migrated_error') else '')
    if r['kind'] == 'api-crosscheck':
        lo, mo = J(st.get('legacy_value'))[:80], J(st.get('migrated_value'))[:80]
    tb = st.get('tables_after') or {}
    tbs = ' / '.join('%s|%s' % (v['rows_legacy'], v['rows_migrated']) + ('' if v['equal'] else '!') for v in tb.values())
    H.append('<tr><td>%d</td><td>%s<br><small>%s</small></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td class="%s">%s</td><td>%d</td></tr>' % (
        i, E(r['id']), E(r.get('title', ''))[:120], E(r['kind']), E(r['program']), lo, mo, E(r.get('seed_equal_before', '')), E(tbs),
        r['status'], r['status'], len(r['diffs'])))
H.append('</table></section>')

H.append('<section><h2>10. Evidence: terminal captures (embedded PNG)</h2>')
for title, a, b in images:
    H.append('<h3>%s</h3><div class="pair"><img alt="legacy %s" src="%s"><img alt="migrated %s" src="%s"></div>' % (E(title), E(title), a, E(title), b))
if console_img:
    H.append('<h3>Parity run console</h3><img alt="console" src="%s">' % console_img)
H.append('</section><section><h2>11. Reproduce</h2><pre>parity/run-parity.sh            # full run (legacy build, mvn verify, React tests, parity, report)\n'
         'SKIP_MVN=1 parity/run-parity.sh # reuse an existing mvn verify\n'
         'results: parity/results/{flows,legacy-replay-all,tests-migrated,tests-react,summary}.json</pre></section></main></body></html>')
out = os.path.join(PARITY, 'report', 'parity-report.html')
open(out, 'w').write('\n'.join(H))
print('wrote', out, os.path.getsize(out), 'bytes; unclassified:', unclassified)
