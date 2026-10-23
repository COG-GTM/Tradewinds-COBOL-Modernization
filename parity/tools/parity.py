#!/usr/bin/env python3
"""Legacy (GnuCOBOL + stub harness) vs migrated (Java 21 / Spring Boot / H2 or Postgres) parity runner.

PARITY_DB=h2 (default) writes results/flows.json; PARITY_DB=postgres (PARITY_PG_URL/USER/PASSWORD) writes
results/flows-postgres.json with the migrated side on the postgres profile.

Every flow is executed on both runtimes from the identical canonical seed (baseline/seed/seed.sql) with the
identical deterministic environment (fixed clock, random seed, EIBTASKN). Compared per flow:
  * outcome      success / business failure (+fail code) / ABEND (+abend code)
  * fields       the business fields of the response, projected to a canonical form (documented below)
  * db_delta     CUSTOMER/ACCOUNT/PROCTRAN/CONTROL inserted/updated/deleted rows
  * tables       SHA-256 of the full normalised table dumps after the flow (and the rows when they differ)
Normalisation (the only one applied): CHAR/VARCHAR right-trimmed, numbers rounded to 2dp, rows sorted.
"""
import concurrent.futures, copy, glob, hashlib, json, os, re, shutil, sqlite3, subprocess, sys, tempfile
import urllib.request, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
PARITY = os.path.dirname(HERE)
ROOT = os.path.dirname(PARITY)
sys.path.insert(0, os.path.join(ROOT, 'legacy', 'tools'))
import harness  # noqa: E402

BASE = None
SERVERS = {}  # CBSA_RANDOM_SEED -> base URL of a ParityServer started with cbsa.random.seed=<seed>
FIXED_TIME = harness.DEFAULT_ENV['CBSA_FIXED_TIME']
TABLES = ['CUSTOMER', 'ACCOUNT', 'PROCTRAN', 'CONTROL']
JAVA = os.path.join(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk-amd64'), 'bin', 'java')
SORTCODE = '987654'
# Migrated database: 'h2' (default profile, in-memory) or 'postgres' (profile postgres, one schema per server).
DB = os.environ.get('PARITY_DB', 'h2')
PG_URL = os.environ.get('PARITY_PG_URL', 'jdbc:postgresql://localhost:5432/cbsa')
PG_USER = os.environ.get('PARITY_PG_USER', 'cbsa')
PG_PASSWORD = os.environ.get('PARITY_PG_PASSWORD', 'cbsa')
RESULTS_FILE = 'flows.json' if DB == 'h2' else 'flows-%s.json' % DB


def pg_recreate_schema(schema):
    """DROP + CREATE the schema so every run starts empty (Spring then runs schema.sql and loads the seed)."""
    m = re.match(r'jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)', PG_URL)
    env = dict(os.environ, PGPASSWORD=PG_PASSWORD)
    subprocess.run(['psql', '-v', 'ON_ERROR_STOP=1', '-q', '-h', m.group(1), '-p', m.group(2) or '5432', '-U', PG_USER,
                    '-d', m.group(3), '-c', 'DROP SCHEMA IF EXISTS %s CASCADE; CREATE SCHEMA %s;' % (schema, schema)],
                   check=True, env=env, capture_output=True, text=True)


def db_args(name):
    """Spring arguments selecting the migrated database for one server / screen context."""
    if DB == 'h2':
        return ['--spring.datasource.url=jdbc:h2:mem:%s;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE' % name]
    if DB == 'postgres':
        schema = 'parity_' + re.sub(r'\W', '_', name).lower()
        pg_recreate_schema(schema)
        return ['--spring.profiles.active=postgres', '--spring.datasource.url=%s?currentSchema=%s' % (PG_URL, schema),
                '--spring.datasource.username=' + PG_USER, '--spring.datasource.password=' + PG_PASSWORD]
    raise ValueError('PARITY_DB must be h2 or postgres, not %r' % DB)


# ---------------------------------------------------------------- normalisation
def nv(v):
    if isinstance(v, bool) or v is None:
        return v
    if isinstance(v, (int, float)):
        return round(float(v), 2)
    if isinstance(v, str):
        return v.rstrip()
    return v


# No column is excluded: PROCTRAN_REF (EIBTASKN) is compared. Java runs every request as one CICS task numbered from
# CBSA_TASKN (TaskNumberSupplier, reset per flow/step through /__parity/{reset,taskn}), as the legacy harness does.
EXCLUDED = set()
ADMIN_TOKEN = 'parity-admin-token'
ABNDINFO = harness.cobcopy.load('ABNDINFO')


def canon(snap):
    out = {}
    for t in TABLES:
        rows = [{k: ('<excluded>' if (t, k) in EXCLUDED else nv(v)) for k, v in r.items()} for r in snap[t]]
        out[t] = sorted(rows, key=lambda r: json.dumps(r, sort_keys=True))
    return out


def digest(rows):
    return hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest()[:16]


def diff(a, b, path=''):
    """field-by-field diff -> [(path, legacy, migrated)]"""
    if isinstance(a, dict) and isinstance(b, dict):
        out = []
        for k in sorted(set(a) | set(b), key=str):
            out += diff(a.get(k, '<absent>'), b.get(k, '<absent>'), '%s.%s' % (path, k) if path else str(k))
        return out
    if isinstance(a, list) and isinstance(b, list):
        out = []
        for i in range(max(len(a), len(b))):
            out += diff(a[i] if i < len(a) else '<absent>', b[i] if i < len(b) else '<absent>', '%s[%d]' % (path, i))
        return out
    return [] if a == b else [(path, a, b)]


# ---------------------------------------------------------------- legacy side
def sqlite_to_migrated(sql):
    """setup_sql in the transcripts is SQLite; translate the two constructs used (printf, recursive CTE) to the
    migrated database (PARITY_DB): H2 SYSTEM_RANGE or PostgreSQL generate_series."""
    sql = re.sub(r"printf\('%0(\d+)d',\s*([^)]+)\)", r"LPAD(CAST(\2 AS VARCHAR), \1, '0')", sql)
    m = re.search(r"FROM\s*\(\s*WITH RECURSIVE\s+(\w+)\((\w+)\)\s+AS\s*\(\s*SELECT\s+(\d+)\s+UNION ALL\s+SELECT\s+\w+\s*\+\s*1\s+"
                  r"FROM\s+\w+\s+WHERE\s+\w+\s*<\s*(\d+)\s*\)\s*SELECT\s+\w+\s+FROM\s+\w+\s*\)", sql, re.I | re.S)
    if m:
        head = re.sub(r'\b%s\b' % m.group(2), 'X', sql[:m.start()])
        rng = 'SYSTEM_RANGE(%s, %s)' if DB == 'h2' else 'generate_series(%s, %s) AS r(X)'
        sql = head + 'FROM ' + rng % (m.group(3), m.group(4)) + sql[m.end():]
    return sql


def legacy_run(steps, setup_sql):
    work = tempfile.mkdtemp(prefix='parity-legacy-')
    try:
        dbp = os.path.join(work, 'cbsa.db')
        harness.fresh_db(dbp)
        for sql in setup_sql or []:
            db = sqlite3.connect(dbp)
            db.executescript(sql)
            db.commit()
            db.close()
        before = canon(harness.snapshot(dbp))
        res = []
        for st in steps:
            case = {k: v for k, v in st.items() if k not in ('setup_sql', 'legacy_program')}
            case['program'] = st.get('legacy_program', st['program'])
            case.setdefault('flow', 'parity')
            case.setdefault('case', 'parity')
            case.setdefault('description', '')
            t = harness.run_case(case, db=dbp)
            res.append({'out': t['commarea_out'], 'abend': t['abend'], 'stderr': t.get('runtime_stderr'),
                        'after': canon(harness.snapshot(dbp)), 'trace': t.get('trace') or []})
        return before, res
    finally:
        shutil.rmtree(work, ignore_errors=True)


# ---------------------------------------------------------------- migrated side
def http(method, path, body=None, ctype='application/json'):
    data = None
    if body is not None:
        data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={'Content-Type': ctype, 'X-CBSA-Admin-Token': ADMIN_TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            status, raw = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read().decode()
    try:
        return status, json.loads(raw) if raw else None
    except ValueError:
        return status, raw


def iso(v):
    if v in (None, '', 0, '0'):
        return None
    s = '%08d' % int(v)
    return '%s-%s-%s' % (s[4:], s[2:4], s[:2])


def rest_request(prog, ci):
    """baseline COMMAREA -> REST call per docs/migration/api-contract.md"""
    n = lambda v: int(v or 0)
    if prog == 'GETCOMPY':
        return 'GET', '/banking/companyName', None
    if prog == 'GETSCODE':
        return 'GET', '/banking/sortCode', None
    if prog == 'INQCUST':
        return 'GET', '/banking/customer/%d' % n(ci.get('INQCUST-CUSTNO')), None
    if prog == 'CRECUST':
        return 'POST', '/banking/customer', {
            'customerName': ci.get('COMM-NAME', ''), 'customerAddress': ci.get('COMM-ADDRESS', ''),
            'dateOfBirth': iso(ci.get('COMM-DATE-OF-BIRTH')), 'sortCode': SORTCODE}
    if prog == 'UPDCUST':
        body = {'customerName': ci.get('COMM-NAME', ''), 'customerAddress': ci.get('COMM-ADDR', ''), 'sortCode': SORTCODE}
        if ci.get('COMM-DOB'):
            body['dateOfBirth'] = iso(ci['COMM-DOB'])
        if ci.get('COMM-CREDIT-SCORE') not in (None, ''):
            body['customerCreditScore'] = str(ci['COMM-CREDIT-SCORE'])
        return 'PUT', '/banking/customer/%d' % n(ci.get('COMM-CUSTNO')), body
    if prog == 'DELCUS':
        return 'DELETE', '/banking/customer/%d' % n(ci.get('COMM-CUSTNO')), None
    if prog == 'INQACC':
        return 'GET', '/banking/account/%d' % n(ci.get('INQACC-ACCNO')), None
    if prog == 'INQACCCU':
        return 'GET', '/banking/account/retrieveByCustomerNumber/%d' % n(ci.get('CUSTOMER-NUMBER')), None
    if prog == 'CREACC':
        return 'POST', '/banking/account', {
            'customerNumber': str(n(ci.get('COMM-CUSTNO'))), 'accountType': ci.get('COMM-ACC-TYPE', ''),
            'interestRate': ci.get('COMM-INT-RT', 0), 'overdraft': ci.get('COMM-OVERDR-LIM', 0), 'sortCode': SORTCODE,
            'availableBalance': ci.get('COMM-AVAIL-BAL', 0), 'actualBalance': ci.get('COMM-ACT-BAL', 0)}
    if prog == 'UPDACC':
        return 'PUT', '/banking/account/%d' % n(ci.get('COMM-ACCNO')), {
            'id': '%08d' % n(ci.get('COMM-ACCNO')), 'accountType': ci.get('COMM-ACC-TYPE', ''),
            'interestRate': ci.get('COMM-INT-RATE', 0), 'overdraft': ci.get('COMM-OVERDRAFT', 0), 'sortCode': SORTCODE,
            'availableBalance': ci.get('COMM-AVAIL-BAL', 0), 'actualBalance': ci.get('COMM-ACTUAL-BAL', 0)}
    if prog == 'DELACC':
        return 'DELETE', '/banking/account/%d' % n(ci.get('DELACC-ACCNO')), None
    if prog == 'DBCRFUN':
        org = ci.get('COMM-ORIGIN', {})
        return 'PUT', '/makepayment/dbcr', {'PAYDBCR': {
            'CommAccno': '%08d' % n(ci.get('COMM-ACCNO')), 'CommAmt': ci.get('COMM-AMT', 0),
            'mSortC': n(ci.get('COMM-SORTC')), 'CommAvBal': 0, 'CommActBal': 0,
            'CommOrigin': {'CommApplid': '', 'CommUserid': '', 'CommFacilityName': '', 'CommNetwrkId': '',
                           'CommFaciltype': n(org.get('COMM-FACILTYPE')), 'Fill0': ''},
            'CommSuccess': ' ', 'CommFailCode': ' '}}
    if prog == 'XFRFUN':
        return 'PUT', '/banking/account/transfer/%d' % n(ci.get('COMM-FACCNO')), {
            'targetAccount': '%08d' % n(ci.get('COMM-TACCNO')), 'amount': ci.get('COMM-AMT', 0)}
    if prog == 'ABNDPROC':  # property-gated admin endpoint (cbsa.abndproc.endpoint.enabled), ABNDINFO COMMAREA
        k = ci.get('ABND-VSAM-KEY', {})
        return 'POST', '/banking/admin/abend', {
            'utimeKey': n(k.get('ABND-UTIME-KEY')), 'taskNoKey': n(k.get('ABND-TASKNO-KEY')),
            'applid': ci.get('ABND-APPLID', ''), 'tranid': ci.get('ABND-TRANID', ''), 'date': ci.get('ABND-DATE', ''),
            'time': ci.get('ABND-TIME', ''), 'code': ci.get('ABND-CODE', ''), 'program': ci.get('ABND-PROGRAM', ''),
            'respCode': n(ci.get('ABND-RESPCODE')), 'resp2Code': n(ci.get('ABND-RESP2CODE')),
            'sqlCode': n(ci.get('ABND-SQLCODE')), 'freeform': ci.get('ABND-FREEFORM', '')}
    if prog == 'TELLER':  # REST teller debit/credit endpoints (same DBCRFUN business rule, facility type 0)
        kind = 'debit' if ci['COMM-AMT'] < 0 else 'credit'
        return 'PUT', '/banking/account/%s/%d' % (kind, n(ci['COMM-ACCNO'])), {'amount': abs(ci['COMM-AMT'])}
    return None


# ---------------------------------------------------------------- canonical projections
LEGACY_FLAG = {'CRECUST': ('COMM-SUCCESS', 'COMM-FAIL-CODE'), 'CREACC': ('COMM-SUCCESS', 'COMM-FAIL-CODE'),
               'INQCUST': ('INQCUST-INQ-SUCCESS', 'INQCUST-INQ-FAIL-CD'), 'INQACC': ('INQACC-SUCCESS', None),
               'INQACCCU': ('COMM-SUCCESS', 'COMM-FAIL-CODE'), 'UPDCUST': ('COMM-UPD-SUCCESS', 'COMM-UPD-FAIL-CD'),
               'UPDACC': ('COMM-SUCCESS', None), 'DELCUS': ('COMM-DEL-SUCCESS', 'COMM-DEL-FAIL-CD'),
               'DELACC': ('DELACC-DEL-SUCCESS', 'DELACC-DEL-FAIL-CD'), 'DBCRFUN': ('COMM-SUCCESS', 'COMM-FAIL-CODE'),
               'TELLER': ('COMM-SUCCESS', 'COMM-FAIL-CODE'), 'XFRFUN': ('COMM-SUCCESS', 'COMM-FAIL-CODE')}


def code(v):
    v = '' if v is None else str(v).strip()
    return None if v in ('', '0') else v


def flat(d, pre=''):
    out = {}
    for k, v in (d or {}).items():
        if isinstance(v, dict):
            for kk, vv in flat(v, k + '.').items():
                out[kk] = vv
                out.setdefault(kk.rsplit('.', 1)[-1], vv)
        else:
            out[pre + k] = v
    return out


def legacy_outcome(prog, out, abend):
    if abend:
        return {'kind': 'ABEND', 'code': abend['code']}
    if prog not in LEGACY_FLAG:
        return {'kind': 'OK', 'code': None}
    s, f = LEGACY_FLAG[prog]
    if out.get(s) == 'Y':
        return {'kind': 'OK', 'code': None}
    return {'kind': 'FAIL', 'code': code(out.get(f)) if f else None}


def migrated_outcome(prog, status, body):
    if prog == 'DBCRFUN' and status == 200 and isinstance(body, dict) and 'PAYDBCR' in body:
        p = body['PAYDBCR']
        if p.get('CommSuccess') == 'Y':
            return {'kind': 'OK', 'code': None}
        return {'kind': 'FAIL', 'code': code(p.get('CommFailCode'))}
    if 200 <= status < 300:
        return {'kind': 'OK', 'code': None}
    b = body if isinstance(body, dict) else {}
    if b.get('abendCode'):
        return {'kind': 'ABEND', 'code': b['abendCode']}
    return {'kind': 'FAIL', 'code': code(b.get('failCode')), 'http': status}


def L_customer(o):
    f = flat(o)
    g = lambda *ks: next((f[k] for k in ks if k in f), None)
    dob = g('COMM-DATE-OF-BIRTH', 'COMM-DOB')
    if 'INQCUST-DOB-DD' in f:
        dob = '%04d-%02d-%02d' % (f['INQCUST-DOB-YYYY'], f['INQCUST-DOB-MM'], f['INQCUST-DOB-DD'])
        rev = '%04d-%02d-%02d' % (f['INQCUST-CS-REVIEW-YYYY'], f['INQCUST-CS-REVIEW-MM'], f['INQCUST-CS-REVIEW-DD'])
    else:
        dob, rev = iso(dob), iso(g('COMM-CS-REVIEW-DATE'))
    return {'number': int(g('COMM-KEY.COMM-NUMBER', 'COMM-CUSTNO', 'INQCUST-CUSTNO') or 0),
            'sortCode': str(g('COMM-KEY.COMM-SORTCODE', 'COMM-SCODE', 'INQCUST-SCODE')),
            'name': nv(g('COMM-NAME', 'INQCUST-NAME')), 'address': nv(g('COMM-ADDRESS', 'COMM-ADDR', 'INQCUST-ADDR')),
            'dateOfBirth': dob, 'creditScore': int(g('COMM-CREDIT-SCORE', 'INQCUST-CREDIT-SCORE') or 0), 'reviewDate': rev}


def M_customer(b):
    b = b.get('customer', b) if isinstance(b, dict) else {}
    return {'number': int(b.get('id') or 0), 'sortCode': str(b.get('sortCode')), 'name': nv(b.get('customerName')),
            'address': nv(b.get('customerAddress')), 'dateOfBirth': b.get('dateOfBirth'),
            'creditScore': int(b.get('customerCreditScore') or 0), 'reviewDate': b.get('customerCreditScoreReviewDate')}


ACC_KEYS = {'number': ('COMM-KEY.COMM-NUMBER', 'COMM-ACCNO', 'INQACC-ACCNO', 'DELACC-ACCNO'),
            'customerNumber': ('COMM-CUSTNO', 'INQACC-CUSTNO', 'DELACC-CUSTNO'),
            'sortCode': ('COMM-KEY.COMM-SORTCODE', 'COMM-SCODE', 'INQACC-SCODE', 'DELACC-SCODE'),
            'type': ('COMM-ACC-TYPE', 'INQACC-ACC-TYPE', 'DELACC-ACC-TYPE'),
            'interestRate': ('COMM-INT-RT', 'COMM-INT-RATE', 'INQACC-INT-RATE', 'DELACC-INT-RATE'),
            'overdraft': ('COMM-OVERDR-LIM', 'COMM-OVERDRAFT', 'INQACC-OVERDRAFT', 'DELACC-OVERDRAFT'),
            'opened': ('COMM-OPENED', 'INQACC-OPENED', 'DELACC-OPENED'),
            'lastStatement': ('COMM-LAST-STMT-DT', 'INQACC-LAST-STMT-DT', 'DELACC-LAST-STMT-DT'),
            'nextStatement': ('COMM-NEXT-STMT-DT', 'INQACC-NEXT-STMT-DT', 'DELACC-NEXT-STMT-DT'),
            'availableBalance': ('COMM-AVAIL-BAL', 'INQACC-AVAIL-BAL', 'DELACC-AVAIL-BAL'),
            'actualBalance': ('COMM-ACT-BAL', 'COMM-ACTUAL-BAL', 'INQACC-ACTUAL-BAL', 'DELACC-ACTUAL-BAL')}


def L_account(o):
    f = flat(o)
    out = {}
    for c, ks in ACC_KEYS.items():
        v = next((f[k] for k in ks if k in f), None)
        if c in ('opened', 'lastStatement', 'nextStatement'):
            v = iso(v)
        elif c in ('number', 'customerNumber'):
            v = int(v or 0)
        elif c == 'sortCode':
            v = str(v)
        else:
            v = nv(v)
        out[c] = v
    return out


def M_account(b):
    b = b if isinstance(b, dict) else {}
    d = lambda v: v
    return {'number': int(b.get('id') or 0), 'customerNumber': int(b.get('customerNumber') or 0),
            'sortCode': str(b.get('sortCode')), 'type': nv(b.get('accountType')), 'interestRate': nv(b.get('interestRate')),
            'overdraft': nv(b.get('overdraft')), 'opened': d(b.get('dateOpened')),
            'lastStatement': d(b.get('lastStatementDate')), 'nextStatement': d(b.get('nextStatementDate')),
            'availableBalance': nv(b.get('availableBalance')), 'actualBalance': nv(b.get('actualBalance'))}


def L_abend(o):
    k = o.get('ABND-VSAM-KEY', {})
    return {'utimeKey': int(k.get('ABND-UTIME-KEY') or 0), 'taskNoKey': int(k.get('ABND-TASKNO-KEY') or 0),
            'applid': nv(o.get('ABND-APPLID')), 'tranid': nv(o.get('ABND-TRANID')), 'date': nv(o.get('ABND-DATE')),
            'time': nv(o.get('ABND-TIME')), 'code': nv(o.get('ABND-CODE')), 'program': nv(o.get('ABND-PROGRAM')),
            'respCode': int(o.get('ABND-RESPCODE') or 0), 'resp2Code': int(o.get('ABND-RESP2CODE') or 0),
            'sqlCode': int(o.get('ABND-SQLCODE') or 0), 'freeform': nv(o.get('ABND-FREEFORM'))}


def M_abend(b):
    b = b if isinstance(b, dict) else {}
    return {'utimeKey': int(b.get('utimeKey') or 0), 'taskNoKey': int(b.get('taskNoKey') or 0),
            'applid': nv(b.get('applid')), 'tranid': nv(b.get('tranid')), 'date': nv(b.get('date')),
            'time': nv(b.get('time')), 'code': nv(b.get('code')), 'program': nv(b.get('program')),
            'respCode': int(b.get('respCode') or 0), 'resp2Code': int(b.get('resp2Code') or 0),
            'sqlCode': int(b.get('sqlCode') or 0), 'freeform': nv(b.get('freeform'))}


def legacy_abndfile(trace):
    """ABNDFILE records the legacy run wrote (stub trace event ABNDFILE, raw record bytes) -> canonical fields."""
    import codecs
    recs = []
    for e in trace:
        if e and e[0] == 'ABNDFILE':
            raw = codecs.escape_decode(e[2].encode('latin-1'))[0]
            recs.append(L_abend(harness.cobcopy.decode(ABNDINFO, raw)))
    return recs


def fields_legacy(prog, o):
    if prog == 'GETCOMPY':
        return {'companyName': nv(o['GETCompanyOperation']['company-name'])}
    if prog == 'GETSCODE':
        return {'sortCode': str(o['GETSORTCODEOperation']['SORTCODE'])}
    if prog in ('CRECUST', 'INQCUST', 'UPDCUST', 'DELCUS'):
        return L_customer(o)
    if prog in ('CREACC', 'INQACC', 'UPDACC', 'DELACC'):
        return L_account(o)
    if prog == 'INQACCCU':
        return {'customerFound': o.get('CUSTOMER-FOUND') == 'Y', 'numberOfAccounts': int(o.get('NUMBER-OF-ACCOUNTS') or 0),
                'accounts': [L_account(a) for a in o.get('ACCOUNT-DETAILS', [])[:int(o.get('NUMBER-OF-ACCOUNTS') or 0)]]}
    if prog in ('DBCRFUN', 'TELLER'):
        return {'availableBalance': nv(o.get('COMM-AV-BAL')), 'actualBalance': nv(o.get('COMM-ACT-BAL'))}
    if prog == 'ABNDPROC':
        return L_abend(o)
    if prog == 'XFRFUN':
        return {'from.availableBalance': nv(o.get('COMM-FAVBAL')), 'from.actualBalance': nv(o.get('COMM-FACTBAL')),
                'to.availableBalance': nv(o.get('COMM-TAVBAL')), 'to.actualBalance': nv(o.get('COMM-TACTBAL'))}
    return {}


def fields_migrated(prog, b):
    b = b if isinstance(b, (dict, list)) else {}
    if prog == 'GETCOMPY':
        return {'companyName': nv(b.get('companyName'))}
    if prog == 'GETSCODE':
        return {'sortCode': str(b.get('sortCode'))}
    if prog in ('CRECUST', 'INQCUST', 'UPDCUST', 'DELCUS'):
        return M_customer(b)
    if prog in ('CREACC', 'INQACC', 'UPDACC', 'DELACC'):
        return M_account(b)
    if prog == 'INQACCCU':
        accs = b.get('accounts', []) or []
        return {'customerFound': bool(b.get('customerFound')), 'numberOfAccounts': int(b.get('numberOfAccounts') or len(accs)),
                'accounts': [M_account(a) for a in accs]}
    if prog == 'DBCRFUN':
        p = b.get('PAYDBCR', {})
        return {'availableBalance': nv(p.get('CommAvBal')), 'actualBalance': nv(p.get('CommActBal'))}
    if prog == 'TELLER':
        return {'availableBalance': nv(b.get('availableBalance')), 'actualBalance': nv(b.get('actualBalance'))}
    if prog == 'ABNDPROC':
        return M_abend(b)
    if prog == 'XFRFUN':
        f, t = b.get('fromAccount', {}) or {}, b.get('toAccount', {}) or {}
        return {'from.availableBalance': nv(f.get('availableBalance')), 'from.actualBalance': nv(f.get('actualBalance')),
                'to.availableBalance': nv(t.get('availableBalance')), 'to.actualBalance': nv(t.get('actualBalance'))}
    return {}


# ---------------------------------------------------------------- comparison
CREATE_PROGS = {'CRECUST', 'CREACC', 'ABNDPROC'}


def expected_http(prog, lo, path=None):
    """HTTP status(es) that docs/migration/api-contract.md prescribes for the legacy outcome."""
    k, c = lo['kind'], lo['code']
    if prog == 'DBCRFUN':
        return {200}
    if k == 'OK':
        return {201} if prog in CREATE_PROGS else {200}
    if k == 'ABEND':
        return {400} if (prog, c) == ('XFRFUN', 'SAME') else {500}
    if prog == 'XFRFUN':
        return {'1': {404}, '2': {404}, '3': {500}, '4': {400}}.get(c, {400, 404})
    if prog == 'TELLER':
        return {'1': {404}, '2': {500}}.get(c, {400})
    if prog == 'DELACC':
        return {'3': {500}}.get(c, {404})
    if prog == 'CRECUST':
        return {500} if c in ('F', 'G') else {400}
    if prog == 'CREACC' and c in ('3', '5', '7', '9'):
        return {500}
    if prog in ('INQACC', 'INQCUST', 'DELCUS', 'INQACCCU') or c == '1':
        return {404}
    if prog == 'UPDACC':
        return {400, 404}
    return {400}


def compare_step(prog, lres, lbefore, mstatus, mbody, mbefore, mafter):
    lo = legacy_outcome(prog, lres['out'], lres['abend'])
    mo = migrated_outcome(prog, mstatus, mbody)
    diffs = []
    if lo['kind'] != mo['kind'] or (lo['code'] != mo['code'] and LEGACY_FLAG.get(prog, (0, 1))[1] is not None):
        diffs.append({'area': 'outcome', 'field': 'outcome', 'legacy': lo, 'migrated': mo})
    exp = expected_http(prog, lo)
    if mstatus not in exp:
        diffs.append({'area': 'http_status', 'field': 'HTTP status (api-contract.md)',
                      'legacy': 'contract for legacy outcome %s %s: %s' % (lo['kind'], lo['code'] or '', sorted(exp)),
                      'migrated': mstatus})
    # PAYDBCR returns a defined COMMAREA payload on business failures too, so its fields are compared either way.
    if lo['kind'] == mo['kind'] == 'OK' or (prog == 'DBCRFUN' and lo['kind'] == mo['kind']):
        for p, a, b in diff(fields_legacy(prog, lres['out']), fields_migrated(prog, mbody)):
            diffs.append({'area': 'response', 'field': p, 'legacy': a, 'migrated': b})
    if prog == 'ABNDPROC':  # the ABNDFILE (VSAM) contents after the step: legacy trace vs GET /banking/admin/abend
        st, recs = http('GET', '/banking/admin/abend')
        for p, a, b in diff(legacy_abndfile(lres.get('trace', [])),
                            [M_abend(r) for r in recs] if st == 200 and isinstance(recs, list) else {'http': st}):
            diffs.append({'area': 'abndfile', 'field': p, 'legacy': a, 'migrated': b})
    ld, md = harness.delta(lbefore, lres['after']), harness.delta(mbefore, mafter)
    for p, a, b in diff(ld, md):
        diffs.append({'area': 'db_delta', 'field': p, 'legacy': a, 'migrated': b})
    tables = {}
    for t in TABLES:
        lh, mh = digest(lres['after'][t]), digest(mafter[t])
        tables[t] = {'rows_legacy': len(lres['after'][t]), 'rows_migrated': len(mafter[t]),
                     'sha_legacy': lh, 'sha_migrated': mh, 'equal': lh == mh}
    return lo, mo, diffs, tables


def run_api_flow(flow):
    """flow: {id, kind, title, steps:[{program, commarea_in, env?}], setup_sql?}"""
    global BASE
    steps = flow['steps']
    BASE = SERVERS[str(steps[0].get('env', {}).get('CBSA_RANDOM_SEED', harness.DEFAULT_ENV['CBSA_RANDOM_SEED']))]
    lbefore, lres = legacy_run(steps, flow.get('setup_sql'))
    taskn = int(steps[0].get('env', {}).get('CBSA_TASKN', 100))
    http('POST', '/__parity/reset?taskn=%d' % taskn)
    for sql in flow.get('setup_sql') or []:
        st, b = http('POST', '/__parity/exec', sqlite_to_migrated(sql), ctype='text/plain')
        if st != 200:
            raise RuntimeError('%s setup SQL failed: %s %s' % (DB, st, b))
    mbefore = canon(http('GET', '/__parity/dump')[1])
    out_steps, all_diffs = [], []
    seed_equal = digest(lbefore) == digest(mbefore)
    for i, st in enumerate(steps):
        prog = st['program']
        if i > 0 and 'CBSA_TASKN' in st.get('env', {}):
            http('POST', '/__parity/taskn?taskn=%s' % st['env']['CBSA_TASKN'])
        req = rest_request(prog, st['commarea_in'])
        mstatus, mbody = http(*req)
        mafter = canon(http('GET', '/__parity/dump')[1])
        prev_l = lbefore if i == 0 else lres[i - 1]['after']
        lo, mo, diffs, tables = compare_step(prog, lres[i], prev_l, mstatus, mbody, mbefore if i == 0 else prev_m, mafter)
        prev_m = mafter
        for d in diffs:
            d['step'] = i + 1
        all_diffs += diffs
        out_steps.append({'step': i + 1, 'program': prog, 'request': {'method': req[0], 'path': req[1], 'body': req[2]},
                          'migrated_http': mstatus, 'migrated_body': mbody, 'legacy_commarea_out': lres[i]['out'],
                          'legacy_abend': lres[i]['abend'], 'legacy_stderr': lres[i]['stderr'],
                          'legacy_outcome': lo, 'migrated_outcome': mo, 'tables_after': tables})
        if any(not v['equal'] for v in tables.values()):
            out_steps[-1]['table_rows_differing'] = {
                t: diff(lres[i]['after'][t], mafter[t]) and {
                    'legacy_only': [r for r in lres[i]['after'][t] if r not in mafter[t]],
                    'migrated_only': [r for r in mafter[t] if r not in lres[i]['after'][t]]}
                for t in TABLES if not tables[t]['equal']}
    return {'id': flow['id'], 'kind': flow['kind'], 'title': flow.get('title', ''), 'program': steps[0]['program'],
            'seed_equal_before': seed_equal, 'status': 'MATCH' if not all_diffs and seed_equal else 'DIFF',
            'diffs': all_diffs, 'steps': out_steps}


# ---------------------------------------------------------------- screens (terminal mode)
def run_screen_flows(paths, java_cp):
    out = os.path.join(tempfile.mkdtemp(prefix='parity-screens-'), 'screens.jsonl')
    subprocess.run([JAVA, '-cp', java_cp, 'ScreenParity', out, '--'] + db_args('parityscreens') + ['--'] + paths, check=True,
                   cwd=os.path.join(ROOT, 'src', 'cbsa-core-service'), capture_output=True, text=True)
    mig = {json.loads(l)['file']: json.loads(l) for l in open(out)}
    results = []
    for p in paths:
        t = json.load(open(p))
        lbefore, lres = legacy_run([t], t.get('setup_sql'))
        m = mig[p]
        lev = [e for e in lres[0]['out']['screen'] if e[0] != 'RETURN_COMMAREA']
        mev = [list(e) for e in (m.get('events') or []) if e[0] != 'RETURN_COMMAREA']
        nf = lambda evs: {e[1]: nv(e[2]) for e in evs if e[0] == 'FIELD'}
        seq = lambda evs: [[str(x) for x in e] for e in evs if e[0] != 'FIELD']
        diffs = []
        if m.get('error'):
            diffs.append({'area': 'outcome', 'field': 'exception', 'legacy': lres[0]['abend'], 'migrated': m['error']})
        for pth, a, b in diff(seq(lev), seq(mev)):
            diffs.append({'area': 'screen_events', 'field': pth, 'legacy': a, 'migrated': b})
        for pth, a, b in diff(nf(lev), nf(mev)):
            diffs.append({'area': 'screen_fields', 'field': pth, 'legacy': a, 'migrated': b})
        mb, ma = canon(m['before']), canon(m['after'])
        for pth, a, b in diff(harness.delta(lbefore, lres[0]['after']), harness.delta(mb, ma)):
            diffs.append({'area': 'db_delta', 'field': pth, 'legacy': a, 'migrated': b})
        tables = {tb: {'rows_legacy': len(lres[0]['after'][tb]), 'rows_migrated': len(ma[tb]),
                       'sha_legacy': digest(lres[0]['after'][tb]), 'sha_migrated': digest(ma[tb]),
                       'equal': digest(lres[0]['after'][tb]) == digest(ma[tb])} for tb in TABLES}
        rel = os.path.relpath(p, os.path.join(ROOT, 'baseline', 'transcripts'))[:-5]
        results.append({'id': rel, 'kind': 'transcript-terminal', 'title': t['description'], 'program': t['program'],
                        'seed_equal_before': digest(lbefore) == digest(mb),
                        'status': 'MATCH' if not diffs and digest(lbefore) == digest(mb) else 'DIFF', 'diffs': diffs,
                        'steps': [{'step': 1, 'program': t['program'], 'input': t['commarea_in'],
                                   'legacy_screen': lev, 'migrated_screen': mev, 'legacy_abend': lres[0]['abend'],
                                   'migrated_error': m.get('error'), 'tables_after': tables}]})
    return results


def run_crosschecks():
    """Read endpoints of the React UI that sit on the Liberty layer (no COBOL program): compared with the same
    query on the legacy harness's seeded SQLite database."""
    work = tempfile.mkdtemp(prefix='parity-xc-')
    dbp = os.path.join(work, 'cbsa.db')
    harness.fresh_db(dbp)
    db = sqlite3.connect(dbp)
    q = lambda sql: [r[0] for r in db.execute(sql)]
    http('POST', '/__parity/reset?taskn=100')
    checks = [
        ('customer-count', '/banking/customer?countOnly=true', lambda b: b.get('numberOfCustomers'),
         q('SELECT COUNT(*) FROM CUSTOMER')[0]),
        ('customer-page', '/banking/customer?limit=5&offset=0', lambda b: [int(c['id']) for c in b.get('customers', [])],
         [int(x) for x in q('SELECT CUSTOMER_NUMBER FROM CUSTOMER ORDER BY CUSTOMER_NUMBER LIMIT 5')]),
        ('customer-name-search', '/banking/customer/name?name=Renton&limit=10',
         lambda b: sorted(int(c['id']) for c in b.get('customers', [])),
         sorted(int(x) for x in q("SELECT CUSTOMER_NUMBER FROM CUSTOMER WHERE CUSTOMER_NAME LIKE '%Renton%' LIMIT 10"))),
        ('account-count', '/banking/account?countOnly=true', lambda b: b.get('numberOfAccounts'),
         q('SELECT COUNT(*) FROM ACCOUNT')[0]),
        ('accounts-of-customer-count', '/banking/account/retrieveByCustomerNumber/1?countOnly=true',
         lambda b: b.get('numberOfAccounts'), q("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_CUSTOMER_NUMBER='0000000001'")[0]),
        ('processed-transactions', '/banking/processedTransaction?limit=1000',
         lambda b: b.get('numberOfProcessedTransactions'), q('SELECT COUNT(*) FROM PROCTRAN')[0]),
        # row-level: every PROCTRAN column the endpoint exposes (PROCTRAN_REF is not exposed), as a sorted
        # multiset because Liberty orders by date+time only and ties have no defined order
        ('processed-transactions-rows', '/banking/processedTransaction?limit=1000',
         lambda b: sorted([p.get('sortCode'), p.get('accountNumber'), p.get('date'), p.get('time'), p.get('type'),
                           p.get('description'), (round(float(p['amount']), 2) if isinstance(p.get('amount'), (int, float)) else p.get('amount'))] for p in b.get('processedTransactions', [])),
         sorted([r[0].strip(), r[1].strip(), str(r[2]), '%s:%s:%s' % (r[3][0:2], r[3][2:4], r[3][4:6]), r[4].strip(),
                 r[5].strip(), round(float(r[6]), 2)] for r in db.execute(
             'SELECT PROCTRAN_SORTCODE, PROCTRAN_NUMBER, PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_TYPE, PROCTRAN_DESC, '
             'PROCTRAN_AMOUNT FROM PROCTRAN'))),
    ]
    out = []
    for cid, path, proj, legacy in checks:
        st, body = http('GET', path)
        mig = proj(body) if st == 200 and isinstance(body, dict) else {'http': st, 'body': body}
        diffs = [] if mig == legacy else [{'area': 'response', 'field': cid, 'legacy': legacy, 'migrated': mig}]
        out.append({'id': 'api-crosscheck/' + cid, 'kind': 'api-crosscheck', 'program': 'Liberty REST (no COBOL)',
                    'title': 'GET %s vs SQL on legacy seeded DB' % path, 'status': 'MATCH' if not diffs else 'DIFF',
                    'diffs': diffs, 'steps': [{'step': 1, 'request': {'method': 'GET', 'path': path}, 'migrated_http': st,
                                               'migrated_value': mig, 'legacy_value': legacy}]})
    db.close()
    shutil.rmtree(work, ignore_errors=True)
    return out


def start_servers(seeds, java_cp):
    import time
    procs = []
    for i, seed in enumerate(seeds):
        port = 18181 + i
        log = open(os.path.join(PARITY, 'build', 'parity-server-%s-%s.log' % (DB, seed)), 'w')
        procs.append(subprocess.Popen(
            [JAVA, '-cp', java_cp, 'ParityServer', '--server.address=127.0.0.1', '--server.port=%d' % port, '--cbsa.clock.fixed=' + FIXED_TIME,
             '--cbsa.random.seed=' + seed, '--cbsa.seed.location=file:../../baseline/seed/seed.sql',
             '--cbsa.abndproc.endpoint.enabled=true', '--cbsa.abndproc.endpoint.token=' + ADMIN_TOKEN] + db_args('parity%s' % seed),
            cwd=os.path.join(ROOT, 'src', 'cbsa-core-service'), stdout=log, stderr=subprocess.STDOUT))
        SERVERS[seed] = 'http://127.0.0.1:%d/webui-1.0' % port
    global BASE
    for seed, base in SERVERS.items():
        BASE = base
        for _ in range(120):
            try:
                if http('GET', '/banking/sortCode')[0] == 200:
                    break
            except OSError:
                pass
            time.sleep(0.5)
        else:
            raise RuntimeError('ParityServer for seed %s did not start' % seed)
    return procs


def main():
    java_cp = sys.argv[1]
    tdir = os.path.join(ROOT, 'baseline', 'transcripts')
    flows, term, na = [], [], []
    for p in sorted(glob.glob(os.path.join(tdir, '*', '*.json'))):
        t = json.load(open(p))
        rel = os.path.relpath(p, tdir)[:-5]
        if t.get('mode') == 'term':
            term.append(p)
        elif t['program'] == 'BANKDATA' or rest_request(t['program'], t['commarea_in']) is None:
            na.append({'id': rel, 'kind': 'transcript-link', 'program': t['program'], 'title': t['description'],
                       'status': 'NOT_COMPARABLE', 'diffs': [], 'steps': []})
        else:
            flows.append({'id': rel, 'kind': 'transcript-link', 'title': t['description'], 'setup_sql': t.get('setup_sql'),
                          'steps': [{'program': t['program'], 'commarea_in': t['commarea_in'], 'env': t.get('env', {})}]})
            if t['program'] == 'DBCRFUN' and int(t['commarea_in'].get('COMM-ORIGIN', {}).get('COMM-FACILTYPE', 0)) != 496 \
                    and t['commarea_in'].get('COMM-AMT'):
                # same teller case through the REST teller endpoints PUT /banking/account/{debit|credit}/{id}
                flows.append({'id': 'TELLER-REST/' + rel.split('/')[1], 'kind': 'api-variant', 'setup_sql': t.get('setup_sql'),
                              'title': 'REST teller endpoint replay of ' + rel,
                              'steps': [{'program': 'TELLER', 'legacy_program': 'DBCRFUN', 'commarea_in': t['commarea_in'],
                                         'env': t.get('env', {})}]})
    for f in json.load(open(os.path.join(PARITY, 'flows', 'user-flows.json')))['flows']:
        flows.append(f)
    seeds = sorted({str(st.get('env', {}).get('CBSA_RANDOM_SEED', '42')) for f in flows for st in f['steps']})
    procs = start_servers(seeds, java_cp)
    results = []
    for f in flows:
        try:
            results.append(run_api_flow(f))
        except Exception as e:  # recorded, never hidden
            results.append({'id': f['id'], 'kind': f['kind'], 'title': f.get('title', ''), 'program': f['steps'][0]['program'],
                            'status': 'ERROR', 'error': repr(e), 'diffs': [], 'steps': []})
        print('%-48s %s' % (results[-1]['id'], results[-1]['status']), flush=True)
    BASE = SERVERS['42']
    for r in run_crosschecks():
        results.append(r)
        print('%-48s %s' % (r['id'], r['status']), flush=True)
    for p in procs:
        p.terminate()
    for r in run_screen_flows(term, java_cp):
        results.append(r)
        print('%-48s %s' % (r['id'], r['status']), flush=True)
    results += na
    json.dump(results, open(os.path.join(PARITY, 'results', RESULTS_FILE), 'w'), indent=1, sort_keys=False)
    summ = {}
    for r in results:
        s = summ.setdefault(r['kind'], {})
        s[r['status']] = s.get(r['status'], 0) + 1
    print(json.dumps(summ))


if __name__ == '__main__':
    main()
