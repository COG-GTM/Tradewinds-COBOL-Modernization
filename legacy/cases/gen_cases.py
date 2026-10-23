#!/usr/bin/env python3
"""Generate legacy/cases/{business,screens}.json: the inputs from which
baseline/transcripts/** are recorded (harness.py record). Account/customer
numbers refer to baseline/seed/seed.sql."""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SC = 987654
B, S = [], []


def case(lst, program, flow, name, desc, ci, **kw):
    c = {'program': program, 'flow': flow, 'case': name, 'description': desc, 'commarea_in': ci}
    c.update(kw)
    lst.append(c)


# ---- reference data
case(B, 'GETCOMPY', 'get-company-name', 'company-name', 'Return the bank company name', {})
case(B, 'GETSCODE', 'get-sortcode', 'sortcode', 'Return the installation sort code', {})

# ---- customer inquiry
for n, d in ((1, 'first customer'), (17, 'mid-range customer')):
    case(B, 'INQCUST', 'inquire-customer', 'found-%d' % n, 'Inquire %s %d' % (d, n),
         {'INQCUST-SCODE': str(SC), 'INQCUST-CUSTNO': n})
case(B, 'INQCUST', 'inquire-customer', 'not-found', 'Inquire non-existent customer 999',
     {'INQCUST-SCODE': str(SC), 'INQCUST-CUSTNO': 999})
case(B, 'INQCUST', 'inquire-customer', 'last-customer', 'Customer number 9999999999 returns the last customer',
     {'INQCUST-SCODE': str(SC), 'INQCUST-CUSTNO': 9999999999})
for seed in ('42', '7'):
    case(B, 'INQCUST', 'random-customer', 'random-seed-%s' % seed,
         'Customer number 0000000000 returns a random existing customer (CBSA_RANDOM_SEED=%s)' % seed,
         {'INQCUST-SCODE': str(SC), 'INQCUST-CUSTNO': 0}, env={'CBSA_RANDOM_SEED': seed})

# ---- create customer (credit check via CRDTAGY1-5)
def cust(name, addr, dob):
    return {'COMM-EYECATCHER': 'CUST', 'COMM-KEY': {'COMM-SORTCODE': SC, 'COMM-NUMBER': 0},
            'COMM-NAME': name, 'COMM-ADDRESS': addr, 'COMM-DATE-OF-BIRTH': dob}


case(B, 'CRECUST', 'create-customer', 'ok-seed-42', 'Create customer; credit check by 5 agencies (seed 42)',
     cust('Mr Alan T Turing', '1 Bletchley Park, Milton Keynes', 23061912), env={'CBSA_RANDOM_SEED': '42'})
case(B, 'CRECUST', 'credit-check', 'ok-seed-7', 'Create customer with a different random seed: different agency scores',
     cust('Mrs Ada L Lovelace', '12 St James Square, London', 10121985), env={'CBSA_RANDOM_SEED': '7'})
case(B, 'CRECUST', 'credit-check', 'ok-seed-1234-taskn-500', 'Credit check with seed 1234 and task number 500',
     cust('Dr Grace B Hopper', '5 Harbour Road, Portsmouth', 9121966), env={'CBSA_RANDOM_SEED': '1234', 'CBSA_TASKN': '500'})
case(B, 'CRECUST', 'create-customer', 'invalid-title', 'Name with an unrecognised title',
     cust('Captain Jack Sparrow', '1 Pearl Quay, Tortuga', 1011980))
case(B, 'CRECUST', 'create-customer', 'invalid-dob-month', 'Date of birth with month 13',
     cust('Ms Jane Q Public', '2 Main Street, York', 15131990))
case(B, 'CRECUST', 'create-customer', 'dob-future', 'Date of birth in the future',
     cust('Mr Future B Born', '3 Time Lane, Bath', 1012030))
case(B, 'CRECUST', 'create-customer', 'dob-too-old', 'Date of birth more than 150 years ago',
     cust('Mr Very O Old', '4 Ancient Way, Ely', 1011800))
case(B, 'CRECUST', 'create-customer', 'dob-pre-1601', 'Date of birth before 1601 (LE CEEDAYS range)',
     cust('Mr Tudor H Eight', '5 Palace Road, Hampton', 28061491))

# ---- update customer
def updc(n, name, addr, **extra):
    d = {'COMM-EYE': 'CUST', 'COMM-SCODE': str(SC), 'COMM-CUSTNO': '%010d' % n, 'COMM-NAME': name, 'COMM-ADDR': addr}
    d.update(extra)
    return d


case(B, 'UPDCUST', 'update-customer', 'name-and-address', 'Update name and address of customer 2',
     updc(2, 'Mr Updated N Name', '99 New Road, Norwich'))
case(B, 'UPDCUST', 'update-customer', 'address-only', 'Update address only (name blank)',
     updc(3, '', '7 Changed Avenue, Derby'))
case(B, 'UPDCUST', 'update-customer', 'not-found', 'Update non-existent customer 999',
     updc(999, 'Mr Nobody', 'Nowhere'))
case(B, 'UPDCUST', 'update-customer', 'blank-name-and-address', 'Both name and address blank',
     updc(4, '', ''))
case(B, 'UPDCUST', 'update-customer', 'invalid-title', 'Name with an unrecognised title',
     updc(5, 'Captain Jack Sparrow', '1 Pearl Quay, Tortuga'))
case(B, 'UPDCUST', 'update-customer', 'ignores-dob-and-score', 'DOB / credit score in COMMAREA are not updated',
     updc(6, 'Mrs Some O Body', '8 Elm Close, Hull', **{'COMM-DOB': 1012000, 'COMM-CREDIT-SCORE': 999}))

# ---- delete customer
def delc(n):
    return {'COMM-EYE': 'CUST', 'COMM-SCODE': str(SC), 'COMM-CUSTNO': '%010d' % n}


case(B, 'DELCUS', 'delete-customer', 'with-4-accounts', 'Delete customer 1 and its 4 accounts', delc(1))
case(B, 'DELCUS', 'delete-customer', 'with-mortgage', 'Delete customer 5 (ISA + MORTGAGE)', delc(5))
case(B, 'DELCUS', 'delete-customer', 'single-account', 'Delete customer 9 (one account)', delc(9))
case(B, 'DELCUS', 'delete-customer', 'not-found', 'Delete non-existent customer 999', delc(999))
case(B, 'DELCUS', 'delete-customer', 'no-accounts', 'Delete customer with no accounts',
     delc(30), setup_sql=["DELETE FROM ACCOUNT WHERE ACCOUNT_CUSTOMER_NUMBER='0000000030';"])

# ---- create account
def acc(n, typ, rate=1.5, od=0, bal=0):
    return {'COMM-EYECATCHER': 'ACCT', 'COMM-CUSTNO': n, 'COMM-KEY': {'COMM-SORTCODE': SC, 'COMM-NUMBER': 0},
            'COMM-ACC-TYPE': typ, 'COMM-INT-RT': rate, 'COMM-OVERDR-LIM': od, 'COMM-AVAIL-BAL': bal, 'COMM-ACT-BAL': bal}


for typ, n, rate, od, bal in (('CURRENT', 9, 0, 250, 0), ('SAVING', 19, 1.75, 0, 100),
                              ('ISA', 29, 2.1, 0, 50), ('LOAN', 23, 17.9, 0, -5000),
                              ('MORTGAGE', 24, 3.5, 0, -125000)):
    case(B, 'CREACC', 'create-account', 'type-%s' % typ.lower(), 'Open a %s account for customer %d' % (typ, n),
         acc(n, typ, rate, od, bal))
case(B, 'CREACC', 'create-account', 'invalid-type', 'Account type CHEQUE is rejected', acc(9, 'CHEQUE'))
case(B, 'CREACC', 'create-account', 'lowercase-type', 'Lower-case account type isa', acc(9, 'isa'))
case(B, 'CREACC', 'create-account', 'customer-not-found', 'Customer 999 does not exist', acc(999, 'SAVING'))
case(B, 'CREACC', 'create-account', 'max-accounts', 'Customer already has 10 accounts',
     acc(30, 'SAVING'), setup_sql=["INSERT INTO ACCOUNT SELECT 'ACCT','0000000030','987654',printf('%08d',100+value),"
                                   "'SAVING',1.0,'2020-01-01',0,'2025-12-01','2026-01-01',10,10 FROM "
                                   "(WITH RECURSIVE n(value) AS (SELECT 1 UNION ALL SELECT value+1 FROM n WHERE value<9) SELECT value FROM n);"])
case(B, 'CREACC', 'create-account', 'ninth-account', 'Customer with 8 accounts opens a 9th',
     acc(29, 'CURRENT', 0, 100), setup_sql=["INSERT INTO ACCOUNT SELECT 'ACCT','0000000029','987654',printf('%08d',200+value),"
                                            "'SAVING',1.0,'2020-01-01',0,'2025-12-01','2026-01-01',10,10 FROM "
                                            "(WITH RECURSIVE n(value) AS (SELECT 1 UNION ALL SELECT value+1 FROM n WHERE value<7) SELECT value FROM n);"])

# ---- inquire account
for a, d in ((1, 'ISA'), (3, 'CURRENT'), (74, 'MORTGAGE')):
    case(B, 'INQACC', 'inquire-account', 'found-%d' % a, 'Inquire %s account %d' % (d, a),
         {'INQACC-SCODE': SC, 'INQACC-ACCNO': a})
case(B, 'INQACC', 'inquire-account', 'not-found', 'Inquire non-existent account 99999',
     {'INQACC-SCODE': SC, 'INQACC-ACCNO': 99999})
case(B, 'INQACC', 'inquire-account', 'last-account', 'Account 99999999 returns the last account',
     {'INQACC-SCODE': SC, 'INQACC-ACCNO': 99999999})

# ---- list accounts for customer
for n, d in ((1, '4 accounts'), (5, 'ISA + MORTGAGE'), (19, '1 account')):
    case(B, 'INQACCCU', 'list-accounts', 'customer-%d' % n, 'List accounts of customer %d (%s)' % (n, d),
         {'CUSTOMER-NUMBER': n})
case(B, 'INQACCCU', 'list-accounts', 'customer-not-found', 'List accounts of non-existent customer 999',
     {'CUSTOMER-NUMBER': 999})
case(B, 'INQACCCU', 'list-accounts', 'no-accounts', 'Existing customer without accounts',
     {'CUSTOMER-NUMBER': 30}, setup_sql=["DELETE FROM ACCOUNT WHERE ACCOUNT_CUSTOMER_NUMBER='0000000030';"])

# ---- update account
def upda(a, typ, rate, od, **extra):
    d = {'COMM-EYE': 'ACCT', 'COMM-SCODE': str(SC), 'COMM-ACCNO': a, 'COMM-ACC-TYPE': typ,
         'COMM-INT-RATE': rate, 'COMM-OVERDRAFT': od}
    d.update(extra)
    return d


case(B, 'UPDACC', 'update-account', 'type-rate-overdraft', 'Change account 2 to CURRENT, rate 0.5, overdraft 300',
     upda(2, 'CURRENT', 0.5, 300))
case(B, 'UPDACC', 'update-account', 'ignores-balance', 'Balances in the COMMAREA are not applied',
     upda(3, 'CURRENT', 0.1, 100, **{'COMM-AVAIL-BAL': 1, 'COMM-ACTUAL-BAL': 1}))
case(B, 'UPDACC', 'update-account', 'not-found', 'Update non-existent account 99999', upda(99999, 'SAVING', 1, 0))
case(B, 'UPDACC', 'update-account', 'blank-type', 'Blank account type', upda(5, '', 1, 0))
case(B, 'UPDACC', 'update-account', 'invalid-type', 'Account type CHEQUE (not validated by UPDACC)',
     upda(6, 'CHEQUE', 1, 0))

# ---- delete account
for a, d in ((2, 'SAVING'), (74, 'MORTGAGE')):
    case(B, 'DELACC', 'delete-account', 'ok-%d' % a, 'Delete %s account %d' % (d, a),
         {'DELACC-SCODE': str(SC), 'DELACC-ACCNO': a})
case(B, 'DELACC', 'delete-account', 'not-found', 'Delete non-existent account 99999',
     {'DELACC-SCODE': str(SC), 'DELACC-ACCNO': 99999})

# ---- debit / credit
def pay(a, amt, ft=0):
    return {'COMM-ACCNO': '%08d' % a, 'COMM-AMT': amt, 'COMM-SORTC': SC, 'COMM-ORIGIN': {'COMM-FACILTYPE': ft}}


case(B, 'DBCRFUN', 'credit', 'credit-teller', 'Teller credit of 100.00 to account 1', pay(1, 100.0))
case(B, 'DBCRFUN', 'debit', 'debit-teller', 'Teller debit of 55.55 from account 7', pay(7, -55.55))
case(B, 'DBCRFUN', 'credit', 'credit-payment-api', 'Payment-interface (FACILTYPE 496) credit to account 9', pay(9, 12.34, 496))
case(B, 'DBCRFUN', 'debit', 'debit-payment-api', 'Payment-interface debit from account 11', pay(11, -10.0, 496))
case(B, 'DBCRFUN', 'debit', 'insufficient-funds-api', 'Payment debit larger than balance + overdraft (account 76)',
     pay(76, -600.0, 496))
case(B, 'DBCRFUN', 'debit', 'insufficient-funds-teller', 'Teller debit larger than balance + overdraft (account 76)',
     pay(76, -600.0))
case(B, 'DBCRFUN', 'debit', 'within-overdraft', 'Payment debit using the overdraft (account 76, limit 500)',
     pay(76, -400.0, 496))
case(B, 'DBCRFUN', 'debit', 'mortgage-payment-api', 'Payment-interface debit from MORTGAGE account 74 is refused',
     pay(74, -10.0, 496))
case(B, 'DBCRFUN', 'credit', 'loan-payment-api', 'Payment-interface credit to LOAN account 4 is refused', pay(4, 10.0, 496))
case(B, 'DBCRFUN', 'credit', 'loan-teller', 'Teller credit to LOAN account 4 is allowed', pay(4, 10.0))
case(B, 'DBCRFUN', 'debit', 'not-found', 'Debit of non-existent account 99999', pay(99999, -1.0))
case(B, 'DBCRFUN', 'credit', 'zero-amount', 'Credit of 0.00', pay(1, 0.0))

# ---- transfer
def xfr(f, t, amt):
    return {'COMM-FACCNO': f, 'COMM-FSCODE': SC, 'COMM-TACCNO': t, 'COMM-TSCODE': SC, 'COMM-AMT': amt}


case(B, 'XFRFUN', 'transfer', 'ok', 'Transfer 250.00 from account 1 to account 2', xfr(1, 2, 250.0))
case(B, 'XFRFUN', 'transfer', 'reverse-order-lock', 'Transfer from higher to lower account number (lock order)', xfr(9, 5, 1.0))
case(B, 'XFRFUN', 'transfer', 'insufficient-funds', 'Transfer more than available from account 76', xfr(76, 1, 600.0))
case(B, 'XFRFUN', 'transfer', 'from-not-found', 'From-account 99999 does not exist', xfr(99999, 1, 1.0))
case(B, 'XFRFUN', 'transfer', 'to-not-found', 'To-account 99999 does not exist', xfr(1, 99999, 1.0))
case(B, 'XFRFUN', 'transfer', 'same-account', 'Transfer to the same account', xfr(3, 3, 10.0))
case(B, 'XFRFUN', 'transfer', 'negative-amount', 'Negative transfer amount', xfr(1, 2, -5.0))
case(B, 'XFRFUN', 'transfer', 'zero-amount', 'Zero transfer amount', xfr(1, 2, 0.0))

# ---- abend handler
case(B, 'ABNDPROC', 'record-abend', 'record', 'ABNDPROC records an abend record (written to ABNDFILE)',
     {'ABND-VSAM-KEY': {'ABND-UTIME-KEY': 123456789, 'ABND-TASKNO-KEY': 42}, 'ABND-APPLID': 'CBSAHARN',
      'ABND-TRANID': 'ODCS', 'ABND-DATE': '15.01.2026', 'ABND-TIME': '10:30:00', 'ABND-CODE': 'HWPT',
      'ABND-PROGRAM': 'INQCUST', 'ABND-RESPCODE': 13, 'ABND-RESP2CODE': 80, 'ABND-SQLCODE': 0,
      'ABND-FREEFORM': 'harness test abend'})


# ---- 3270 screens (pseudo-conversational: first entry has no COMMAREA).
# Field values are what the terminal transmits: a field pre-filled with blanks
# is sent with its trailing blanks, so its length (xxxL) is the full width.
def scr(prog, tran, name, desc, aid='ENTER', fields=None, first=False):
    ci = {'aid': aid}
    if not first:
        ci['commarea'] = 'X'
    if fields:
        ci['map_fields'] = fields
    case(S, prog, 'screen-' + tran.lower(), name, desc, ci, mode='term', transid=tran)


scr('BNKMENU', 'OMEN', 'first-display', 'Initial menu display', first=True)
for a, t in (('1', 'ODCS'), ('2', 'ODAC'), ('3', 'OCCS'), ('4', 'OCAC'), ('5', 'OUAC'), ('6', 'OCRA'), ('7', 'OTFN'), ('A', 'OCCA')):
    scr('BNKMENU', 'OMEN', 'action-%s' % a.lower(), 'Menu option %s routes to transaction %s' % (a, t), fields={'ACTION': a})
scr('BNKMENU', 'OMEN', 'invalid-action', 'Invalid menu option 9', fields={'ACTION': '9'})
scr('BNKMENU', 'OMEN', 'pf3-exit', 'PF3 ends the session', aid='PF3')
scr('BNKMENU', 'OMEN', 'clear', 'CLEAR key', aid='CLEAR')
scr('BNK1DCS', 'ODCS', 'first-display', 'Display/delete customer screen first entry', first=True)
scr('BNK1DCS', 'ODCS', 'lookup-found', 'Look up customer 2', fields={'CUSTNO': '0000000002'})
scr('BNK1DCS', 'ODCS', 'lookup-not-found', 'Look up customer 999', fields={'CUSTNO': '0000000999'})
scr('BNK1DCS', 'ODCS', 'lookup-non-numeric', 'Non-numeric customer number', fields={'CUSTNO': 'ABC'})
scr('BNK1CCS', 'OCCS', 'first-display', 'Create customer screen first entry', first=True)
scr('BNK1CCS', 'OCCS', 'create-ok', 'Create customer from the screen',
    fields={'CUSTTIT': 'Mr', 'CHRISTN': 'Screen', 'CUSTINS': 'T', 'CUSTSN': 'Tester', 'CUSTAD1': '10 Map Street',
            'CUSTAD2': 'Leeds', 'DOBDD': '01', 'DOBMM': '02', 'DOBYY': '1980'})
scr('BNK1CCS', 'OCCS', 'invalid-title', 'Create customer with invalid title',
    fields={'CUSTTIT': 'King', 'CHRISTN': 'Arthur', 'CUSTINS': 'P', 'CUSTSN': 'Pendragon', 'CUSTAD1': '1 Castle',
            'DOBDD': '01', 'DOBMM': '01', 'DOBYY': '1970'})
scr('BNK1CAC', 'OCAC', 'first-display', 'Create account screen first entry', first=True)
scr('BNK1CAC', 'OCAC', 'create-ok', 'Open SAVING account for customer 19',
    fields={'CUSTNO': '0000000019', 'ACCTYP': 'SAVING  ', 'INTRT': '1.25   ', 'OVERDR': '0       '})
scr('BNK1CAC', 'OCAC', 'invalid-type', 'Open account with type CHEQUE',
    fields={'CUSTNO': '0000000019', 'ACCTYP': 'CHEQUE  ', 'INTRT': '1.25   ', 'OVERDR': '0       '})
scr('BNK1DAC', 'ODAC', 'lookup-found', 'Display account 3', fields={'ACCNO': '00000003'})
scr('BNK1DAC', 'ODAC', 'lookup-not-found', 'Display account 99999', fields={'ACCNO': '00099999'})
scr('BNK1UAC', 'OUAC', 'lookup', 'Update account screen: look up account 3', fields={'ACCNO': '00000003'})
scr('BNK1CRA', 'OCRA', 'credit', 'Credit 20.00 to account 1', fields={'ACCNO': '00000001', 'SIGN': '+', 'AMT': '20.00'})
scr('BNK1CRA', 'OCRA', 'debit-insufficient', 'Debit 900.00 from account 76',
    fields={'ACCNO': '00000076', 'SIGN': '-', 'AMT': '900.00'})
scr('BNK1TFN', 'OTFN', 'transfer-ok', 'Transfer 10.00 from 1 to 2', fields={'FACCNO': '00000001', 'TACCNO': '00000002', 'AMT': '10.00'})
scr('BNK1TFN', 'OTFN', 'non-numeric-amount', 'Non-numeric amount',
    fields={'FACCNO': '00000001', 'TACCNO': '00000002', 'AMT': 'ten'})
scr('BNK1CCA', 'OCCA', 'list-accounts', 'List accounts of customer 1', fields={'CUSTNO': '0000000001'})
scr('BNK1CCA', 'OCCA', 'customer-not-found', 'List accounts of customer 999', fields={'CUSTNO': '0000000999'})

for fn, lst in (('business.json', B), ('screens.json', S)):
    with open(os.path.join(HERE, fn), 'w') as f:
        json.dump(lst, f, indent=1)
        f.write('\n')
print('business=%d screens=%d' % (len(B), len(S)))
