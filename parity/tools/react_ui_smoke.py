#!/usr/bin/env python3
"""React UI end-to-end check: the unmodified Carbon React build (served by cbsa-core-service from static/) is driven in
headless Chromium against the migrated service; every value the UI renders is compared with the same record in the
legacy harness's seeded database (the data the legacy Liberty UI would render). Screenshots are kept for the report.
Usage: react_ui_smoke.py <java classpath incl. parity/build/classes>"""
import base64, json, os, sqlite3, subprocess, sys, tempfile, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PARITY = os.path.dirname(HERE)
ROOT = os.path.dirname(PARITY)
sys.path.insert(0, os.path.join(ROOT, 'legacy', 'tools'))
import harness  # noqa: E402

PORT = 18190
BASE = 'http://127.0.0.1:%d/webui-1.0/' % PORT
JAVA = os.path.join(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk-amd64'), 'bin', 'java')
dmy = lambda iso: '%s-%s-%s' % (iso[8:10], iso[5:7], iso[0:4])


def num_eq(a, b):
    try:
        return abs(float(a) - float(b)) < 0.005
    except (TypeError, ValueError):
        return str(a).strip() == str(b).strip()


def legacy_db():
    d = tempfile.mkdtemp(prefix='parity-ui-')
    p = os.path.join(d, 'cbsa.db')
    harness.fresh_db(p)
    db = sqlite3.connect(p)
    db.row_factory = sqlite3.Row
    return db


def table_rows(page):
    heads = [h.inner_text().strip() for h in page.query_selector_all('table thead th')]
    rows = []
    for tr in page.query_selector_all('table tbody tr'):
        cells = [c.inner_text().strip() for c in tr.query_selector_all('td')]
        if cells:
            rows.append(cells)
    return heads, rows


def compare(expected, cells):
    """expected: ordered (field, legacy value); cells: rendered row (leading expand cell dropped by matching tail)."""
    cells = cells[-len(expected):]
    out = []
    for (field, lv), mv in zip(expected, cells):
        out.append({'field': field, 'legacy': lv, 'migrated_ui': mv, 'equal': num_eq(lv, mv)})
    return out


def main():
    cp = sys.argv[1]
    log = open(os.path.join(PARITY, 'build', 'react-ui-server.log'), 'w')
    proc = subprocess.Popen([JAVA, '-cp', cp, 'ParityServer', '--server.address=127.0.0.1', '--server.port=%d' % PORT,
                             '--cbsa.clock.fixed=' + harness.DEFAULT_ENV['CBSA_FIXED_TIME'], '--cbsa.random.seed=42',
                             '--cbsa.seed.location=file:../../baseline/seed/seed.sql',
                             '--spring.datasource.url=jdbc:h2:mem:parityui;MODE=PostgreSQL;DB_CLOSE_DELAY=-1'],
                            cwd=os.path.join(ROOT, 'src', 'cbsa-core-service'), stdout=log, stderr=subprocess.STDOUT)
    results, shots = [], []
    try:
        for _ in range(120):
            try:
                urllib.request.urlopen(BASE + 'banking/sortCode', timeout=2)
                break
            except OSError:
                time.sleep(0.5)
        else:
            raise RuntimeError('migrated service did not start')
        db = legacy_db()
        from playwright.sync_api import sync_playwright
        with sync_playwright() as pw:
            br = pw.chromium.launch()
            page = br.new_page(viewport={'width': 1400, 'height': 1000})
            api = []
            page.on('response', lambda r: api.append({'url': r.url.replace(BASE, '/webui-1.0/'), 'status': r.status})
                    if '/banking/' in r.url else None)

            def shot(name):
                png = page.screenshot(full_page=True, type='jpeg', quality=70)
                shots.append({'name': name, 'jpeg_b64': base64.b64encode(png).decode()})

            def record(fid, title, expected, rendered_rows, extra=None):
                cmp = compare(expected, rendered_rows[0]) if rendered_rows else []
                ok = bool(cmp) and all(c['equal'] for c in cmp) and (extra is None or extra['equal'])
                results.append({'id': 'react-ui/' + fid, 'title': title, 'status': 'MATCH' if ok else 'DIFF',
                                'fields': cmp + ([extra] if extra else []), 'api_calls': list(api)})
                api.clear()

            # 1. home + control panel render
            page.goto(BASE + '#/profile/Admin')
            page.wait_for_load_state('networkidle')
            shot('Control panel (#/profile/Admin)')

            # 2. customer details by number (INQCUST + INQACCCU behind the UI)
            c = db.execute("SELECT * FROM CUSTOMER WHERE CUSTOMER_NUMBER='0000000001'").fetchone()
            page.goto(BASE + '#/Admin/customer_details')
            page.fill('#customerNum', '1')
            page.click('button[type=submit]')
            page.wait_for_selector('table tbody tr', timeout=15000)
            page.wait_for_load_state('networkidle')
            _, rows = table_rows(page)
            exp = [('customerNumber', str(int(c['CUSTOMER_NUMBER']))), ('sortCode', c['CUSTOMER_SORTCODE']),
                   ('customerName', c['CUSTOMER_NAME'].strip()), ('customerAddress', c['CUSTOMER_ADDRESS'].strip()),
                   ('formattedDOB', dmy(str(c['CUSTOMER_DATE_OF_BIRTH']))), ('creditScore', str(c['CUSTOMER_CREDIT_SCORE'])),
                   ('formattedReviewDate', dmy(str(c['CUSTOMER_CS_REVIEW_DATE'])))]
            shot('Customer details: customer 1')
            record('customer-details-by-number', 'Customer details page, search customer number 1', exp, rows)

            # 3. expanded row: accounts of customer 1
            exp_btn = page.query_selector('table tbody tr button')
            n_legacy = db.execute("SELECT COUNT(*) FROM ACCOUNT WHERE ACCOUNT_CUSTOMER_NUMBER='0000000001'").fetchone()[0]
            if exp_btn:
                exp_btn.click()
                page.wait_for_timeout(800)
            body = page.inner_text('table')
            legacy_accts = [r['ACCOUNT_NUMBER'] for r in db.execute(
                "SELECT ACCOUNT_NUMBER FROM ACCOUNT WHERE ACCOUNT_CUSTOMER_NUMBER='0000000001' ORDER BY ACCOUNT_NUMBER")]
            shown = [a for a in legacy_accts if a in body or str(int(a)) in body.split()]
            shot('Customer details: customer 1, accounts expanded')
            results.append({'id': 'react-ui/customer-accounts-expanded', 'title': 'Expanded accounts of customer 1',
                            'status': 'MATCH' if exp_btn and len(shown) == n_legacy else 'DIFF',
                            'fields': [{'field': 'accounts listed', 'legacy': legacy_accts, 'migrated_ui': shown,
                                        'equal': bool(exp_btn) and len(shown) == n_legacy}], 'api_calls': list(api)})
            api.clear()

            # 4. customer search by name (Liberty name search)
            page.goto(BASE + '#/Admin/customer_details')
            page.reload()
            page.fill('#customerNameInput', 'Renton')
            page.click('button[type=submit]')
            page.wait_for_selector('table tbody tr', timeout=15000)
            page.wait_for_load_state('networkidle')
            _, rows = table_rows(page)
            ui_ids = sorted(int(r[-7]) for r in rows if len(r) >= 7 and r[-7].isdigit())
            leg_ids = sorted(int(r[0]) for r in db.execute(
                "SELECT CUSTOMER_NUMBER FROM CUSTOMER WHERE CUSTOMER_NAME LIKE '%Renton%' LIMIT 10"))
            shot('Customer details: name search "Renton"')
            results.append({'id': 'react-ui/customer-search-by-name', 'title': 'Customer details page, name search Renton',
                            'status': 'MATCH' if ui_ids == leg_ids and leg_ids else 'DIFF',
                            'fields': [{'field': 'customer numbers listed', 'legacy': leg_ids, 'migrated_ui': ui_ids,
                                        'equal': ui_ids == leg_ids}], 'api_calls': list(api)})
            api.clear()

            # 5. account details (INQACC behind the UI)
            a = db.execute("SELECT * FROM ACCOUNT WHERE ACCOUNT_NUMBER='00000001'").fetchone()
            page.goto(BASE + '#/Admin/account_details')
            page.reload()
            page.fill('#accountNum', '1')
            page.click('button[type=submit]')
            page.wait_for_selector('table tbody tr', timeout=15000)
            page.wait_for_load_state('networkidle')
            _, rows = table_rows(page)
            exp = [('customerNumber', str(int(a['ACCOUNT_CUSTOMER_NUMBER']))), ('accountNumber', a['ACCOUNT_NUMBER']),
                   ('sortCode', a['ACCOUNT_SORTCODE']), ('accountType', a['ACCOUNT_TYPE'].strip()),
                   ('interestRate', a['ACCOUNT_INTEREST_RATE']), ('overdraft', a['ACCOUNT_OVERDRAFT_LIMIT']),
                   ('availableBalance', a['ACCOUNT_AVAILABLE_BALANCE']), ('actualBalance', a['ACCOUNT_ACTUAL_BALANCE']),
                   ('formattedDateOpened', dmy(str(a['ACCOUNT_OPENED']))),
                   ('formattedLastStatementDue', dmy(str(a['ACCOUNT_LAST_STATEMENT']))),
                   ('formattedNextStatementDue', dmy(str(a['ACCOUNT_NEXT_STATEMENT'])))]
            shot('Account details: account 1')
            record('account-details-by-number', 'Account details page, search account number 1', exp, rows)
            br.close()
    except Exception as e:  # recorded, never hidden
        results.append({'id': 'react-ui/ERROR', 'title': 'React UI run failed', 'status': 'ERROR', 'error': repr(e),
                        'fields': [], 'api_calls': []})
    finally:
        proc.terminate()
    json.dump({'base': '/webui-1.0/ (static React build in cbsa-core-service, served by the migrated service)',
               'browser': 'Chromium headless (Playwright)', 'results': results, 'screenshots': shots},
              open(os.path.join(PARITY, 'results', 'react-ui.json'), 'w'), indent=1)
    for r in results:
        print('%-48s %s' % (r['id'], r['status']))


if __name__ == '__main__':
    main()
