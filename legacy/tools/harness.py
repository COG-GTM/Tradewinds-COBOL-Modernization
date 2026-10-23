#!/usr/bin/env python3
"""CBSA legacy harness driver.

  harness.py record <cases.json> [--out baseline/transcripts]   run cases, write transcripts
  harness.py replay <transcript.json> [--quiet]                 re-run, compare, print MATCH/DIFF
  harness.py replay-all <dir> <results.json>                    replay every transcript

Each run starts from a fresh copy of the seed database
(baseline/seed/schema-legacy-harness.sql + baseline/seed/seed.sql)."""
import copy
import glob
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
LEGACY = os.path.dirname(HERE)
ROOT = os.path.dirname(LEGACY)
BUILD = os.path.join(LEGACY, 'build')
sys.path.insert(0, HERE)
import cobcopy  # noqa: E402

COPYBOOK = {'CRECUST': 'CRECUST', 'CREACC': 'CREACC', 'INQCUST': 'INQCUST', 'INQACC': 'INQACC',
            'INQACCCU': 'INQACCCU', 'UPDCUST': 'UPDCUST', 'UPDACC': 'UPDACC', 'DELCUS': 'DELCUS',
            'DELACC': 'DELACC', 'DBCRFUN': 'PAYDBCR', 'XFRFUN': 'XFRFUN', 'GETCOMPY': 'GETCOMPY',
            'GETSCODE': 'GETSCODE', 'ABNDPROC': 'ABNDINFO'}
TABLES = {'CUSTOMER': ('CUSTOMER_SORTCODE', 'CUSTOMER_NUMBER'), 'ACCOUNT': ('ACCOUNT_SORTCODE', 'ACCOUNT_NUMBER'),
          'PROCTRAN': None, 'CONTROL': ('CONTROL_NAME',)}
TRACE_EVENTS = ('LINK', 'RUN_TRANSID', 'CHILD_DONE', 'FETCH_CHILD', 'ROLLBACK', 'UNHANDLED_RESP', 'SQLERROR',
                'UNSUPPORTED', 'ABNDFILE', 'RETURN_TRANSID')
SCREEN_EVENTS = ('SEND_MAP', 'FIELD', 'SEND_TEXT', 'SEND_CONTROL', 'RECEIVE_MAP', 'RETURN_TRANSID', 'RETURN_COMMAREA')
DEFAULT_ENV = {'CBSA_FIXED_TIME': '2026-01-15T10:30:00Z', 'CBSA_RANDOM_SEED': '42', 'CBSA_TASKN': '100'}


def fresh_db(path):
    tmpl = os.path.join(BUILD, 'fresh.db')
    seed = os.path.join(ROOT, 'baseline', 'seed', 'seed.sql')
    schema = os.path.join(ROOT, 'baseline', 'seed', 'schema-legacy-harness.sql')
    newest = max(os.path.getmtime(seed), os.path.getmtime(schema))
    if not os.path.exists(tmpl) or os.path.getmtime(tmpl) < newest:
        if os.path.exists(tmpl):
            os.remove(tmpl)
        db = sqlite3.connect(tmpl)
        db.executescript(open(schema).read())
        db.executescript(open(seed).read())
        db.commit()
        db.close()
    shutil.copy(tmpl, path)


def norm(v):
    if isinstance(v, float):
        return round(v, 2)
    if isinstance(v, str):
        return v.rstrip()
    return v


def snapshot(path):
    db = sqlite3.connect(path)
    snap = {}
    for t in TABLES:
        cur = db.execute('SELECT * FROM %s ORDER BY rowid' % t)
        cols = [d[0] for d in cur.description]
        snap[t] = [{c: norm(v) for c, v in zip(cols, r)} for r in cur]
    db.close()
    return snap


def delta(before, after):
    out = {}
    for t, key in TABLES.items():
        b, a = before[t], after[t]
        d = {'inserted': [], 'updated': [], 'deleted': []}
        if key is None:
            bl = [json.dumps(r, sort_keys=True) for r in b]
            for r in a:
                s = json.dumps(r, sort_keys=True)
                if s in bl:
                    bl.remove(s)
                else:
                    d['inserted'].append(r)
            d['deleted'] = [json.loads(s) for s in bl]
        else:
            bm = {tuple(r[k] for k in key): r for r in b}
            am = {tuple(r[k] for k in key): r for r in a}
            for k, r in am.items():
                if k not in bm:
                    d['inserted'].append(r)
                elif r != bm[k]:
                    d['updated'].append({'key': dict(zip(key, k)),
                                         'changes': {c: [bm[k][c], r[c]] for c in r if r[c] != bm[k][c]}})
            d['deleted'] = [r for k, r in bm.items() if k not in am]
        out[t] = d
    return out


def parse_events(path):
    evs = []
    if os.path.exists(path):
        for line in open(path, encoding='latin-1'):
            evs.append(line.rstrip('\n').split('\t'))
    return evs


def run_case(case, db=None):
    prog = case['program']
    mode = case.get('mode', 'link')
    env = dict(DEFAULT_ENV)
    env.update(case.get('env', {}))
    work = tempfile.mkdtemp(prefix='cbsa-')
    try:
        dbp = db or os.path.join(work, 'cbsa.db')
        if not db:
            fresh_db(dbp)
        for sql in case.get('setup_sql', []):
            db = sqlite3.connect(dbp)
            db.executescript(sql)
            db.commit()
            db.close()
        before = snapshot(dbp)
        layout = cobcopy.load(COPYBOOK[prog]) if mode == 'link' else None
        ci = case.get('commarea_in', {})
        if mode == 'link':
            raw = bytes(cobcopy.encode(layout, ci))
        else:
            raw = ci.get('commarea', '').encode('latin-1')
            with open(os.path.join(work, 'mapin'), 'w') as f:
                for k, v in ci.get('map_fields', {}).items():
                    f.write('%s=%s\n' % (k, v))
        with open(os.path.join(work, 'in'), 'wb') as f:
            f.write(raw)
        penv = dict(os.environ)
        penv.update(env)
        penv['CBSA_LIB'] = os.path.join(BUILD, 'lib')
        if mode == 'term':
            penv['CBSA_AID'] = ci.get('aid', 'ENTER')
            penv['CBSA_MAPIN'] = os.path.join(work, 'mapin')
            penv['CBSA_TRANSID'] = case.get('transid', 'CBSA')
        p = subprocess.run([os.path.join(BUILD, 'cbsarun'), dbp, mode, prog, os.path.join(work, 'in'),
                            os.path.join(work, 'out'), os.path.join(work, 'events')],
                           cwd=work, env=penv, capture_output=True, text=True, timeout=120)
        evs = parse_events(os.path.join(work, 'events'))
        after = snapshot(dbp)
        out_raw = open(os.path.join(work, 'out'), 'rb').read() if os.path.exists(os.path.join(work, 'out')) else raw
        abend = next(({'code': e[1], 'program': e[2]} for e in evs if e[0] == 'ABEND'), None)
        if mode == 'link':
            cout = cobcopy.decode(layout, out_raw)
        else:
            cout = {'screen': [e for e in evs if e[0] in SCREEN_EVENTS]}
        t = {
            'program': prog, 'flow': case['flow'], 'case': case['case'], 'description': case['description'],
            'runtime': 'gnucobol-stub', 'mode': mode, 'env': env,
            'commarea_in': ci, 'commarea_out': cout, 'abend': abend,
            'db_delta': delta(before, after),
            'trace': [e for e in evs if e[0] in TRACE_EVENTS],
        }
        if case.get('setup_sql'):
            t['setup_sql'] = case['setup_sql']
        if mode == 'term':
            t['transid'] = case.get('transid', 'CBSA')
        if p.returncode != 0 or p.stderr.strip():
            t['runtime_stderr'] = ('exit=%d ' % p.returncode) + p.stderr.strip()[-2000:]
        return t
    finally:
        shutil.rmtree(work, ignore_errors=True)


# runtime_stderr is absent in every recorded transcript, so any process failure
# (nonzero exit or stderr from cbsarun) is reported as a DIFF.
COMPARED = ('commarea_out', 'abend', 'db_delta', 'trace', 'runtime_stderr')


def diff(a, b, path=''):
    out = []
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a:
                out.append('%s.%s: missing in expected, actual=%r' % (path, k, b[k]))
            elif k not in b:
                out.append('%s.%s: expected=%r, missing in actual' % (path, k, a[k]))
            else:
                out += diff(a[k], b[k], '%s.%s' % (path, k))
    elif isinstance(a, list) and isinstance(b, list) and len(a) == len(b):
        for i, (x, y) in enumerate(zip(a, b)):
            out += diff(x, y, '%s[%d]' % (path, i))
    elif a != b:
        out.append('%s: expected=%r actual=%r' % (path, a, b))
    return out


def replay(path, quiet=False):
    exp = json.load(open(path))
    if exp.get('runtime') == 'golden-fixture':
        if not quiet:
            print('SKIP (golden fixture) %s' % path)
        return 'fixture', []
    act = run_case(exp)
    d = []
    for k in COMPARED:
        d += diff(exp.get(k), act.get(k), k)
    if not quiet:
        print(('MATCH ' if not d else 'DIFF  ') + path)
        for line in d[:50]:
            print('   ' + line)
    return ('match' if not d else 'diff'), d


def main():
    cmd = sys.argv[1]
    if cmd == 'record':
        out = sys.argv[3] if len(sys.argv) > 3 else os.path.join(ROOT, 'baseline', 'transcripts')
        cases = json.load(open(sys.argv[2]))
        for c in cases:
            t = run_case(c)
            d = os.path.join(out, c['program'])
            os.makedirs(d, exist_ok=True)
            with open(os.path.join(d, c['case'] + '.json'), 'w') as f:
                json.dump(t, f, indent=2)
                f.write('\n')
            flag = ' ABEND %s' % t['abend']['code'] if t['abend'] else ''
            err = ' STDERR' if t.get('runtime_stderr') else ''
            print('recorded %s/%s%s%s' % (c['program'], c['case'], flag, err))
    elif cmd == 'apply':
        # run cases directly against an existing database (used by make-seed.sh)
        for c in json.load(open(sys.argv[3])):
            t = run_case(c, db=os.path.abspath(sys.argv[2]))
            co = t['commarea_out']
            ok = not t['abend'] and not t.get('runtime_stderr') and co.get('COMM-SUCCESS', 'Y') == 'Y'
            print('applied %s/%s %s' % (c['program'], c['case'], 'ok' if ok else 'FAILED'))
            if not ok:
                sys.exit(1)
    elif cmd == 'replay':
        st, _ = replay(sys.argv[2], '--quiet' in sys.argv)
        sys.exit(0 if st in ('match', 'fixture') else 1)
    elif cmd == 'replay-all':
        files = sorted(glob.glob(os.path.join(sys.argv[2], '*', '*.json')))
        res = {'total': 0, 'match': 0, 'diff': 0, 'golden_fixture': 0, 'by_program': {}, 'diffs': {}}
        for f in files:
            st, d = replay(f)
            prog = os.path.basename(os.path.dirname(f))
            bp = res['by_program'].setdefault(prog, {'total': 0, 'match': 0, 'diff': 0, 'golden_fixture': 0})
            key = 'golden_fixture' if st == 'fixture' else st
            for r in (res, bp):
                r['total'] += 1
                r[key] += 1
            if d:
                res['diffs'][os.path.relpath(f, ROOT)] = d[:20]
        with open(sys.argv[3], 'w') as fh:
            json.dump(res, fh, indent=2)
            fh.write('\n')
        print('total=%d match=%d diff=%d golden_fixture=%d' % (res['total'], res['match'], res['diff'],
                                                               res['golden_fixture']))
        sys.exit(0 if res['diff'] == 0 else 1)


if __name__ == '__main__':
    main()
