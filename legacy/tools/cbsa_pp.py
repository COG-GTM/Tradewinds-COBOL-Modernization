#!/usr/bin/env python3
"""CBSA legacy preprocessor: rewrites EXEC CICS / EXEC SQL into CALLs to the
stub runtime (legacy/runtime/cbsastub.c), the way the CICS/DB2 translators do.

Output: <out>/<PROG>.cbl (fixed format, compiles with cobc) and
<out>/<PROG>.meta (one line per translated command, read by the runtime).
The business logic is not touched; only these mechanical rewrites happen:
  * EXEC CICS ... END-EXEC   -> CALL 'CBSACICS' USING 'PROG' id args...
  * EXEC SQL ... END-EXEC    -> CALL 'CBSASQL'  USING 'PROG' id SQLCA hostvars...
  * EXEC SQL INCLUDE x       -> copybook inlined (SQLCA from legacy/copy)
  * DFHRESP(x) / DFHVALUE(x) -> numeric constants (as the translator does)
  * FUNCTION RANDOM[(seed)]  -> CBSA-RND-VAL filled by CALL 'CBSARND[S]'
  * PROCEDURE DIVISION [USING DFHCOMMAREA] -> USING DFHEIBLK DFHCOMMAREA
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
COPY_DIRS = [os.path.join(ROOT, 'legacy', 'copy'),
             os.path.join(ROOT, 'src', 'base', 'cobol_copy')]

# CICS EIBRESP values (subset used by CBSA) and CVDA values.
DFHRESP = {
    'NORMAL': 0, 'ERROR': 1, 'RDATT': 2, 'WRBRK': 3, 'EOF': 4, 'EODS': 5,
    'EOC': 6, 'INBFMH': 7, 'ENDINPT': 8, 'NONVAL': 9, 'NOSTART': 10,
    'TERMIDERR': 11, 'FILENOTFOUND': 12, 'NOTFND': 13, 'DUPREC': 14,
    'DUPKEY': 15, 'INVREQ': 16, 'IOERR': 17, 'NOSPACE': 18, 'NOTOPEN': 19,
    'ENDFILE': 20, 'ILLOGIC': 21, 'LENGERR': 22, 'QZERO': 23, 'SIGNAL': 24,
    'QBUSY': 25, 'ITEMERR': 26, 'PGMIDERR': 27, 'TRANSIDERR': 28,
    'ENDDATA': 29, 'INVTSREQ': 30, 'EXPIRED': 31, 'MAPFAIL': 36,
    'INVMPSZ': 38, 'SYSIDERR': 53, 'NOTAUTH': 70, 'DISABLED': 84,
    'CONTAINERERR': 110, 'CHANNELERR': 122, 'TOKENERR': 112,
    'ACTIVITYERR': 109, 'NOTFINISHED': 125,
}
DFHVALUE = {
    'NORMAL': 1018, 'ABEND': 1019, 'SECERROR': 1020, 'UCTRAN': 902,
    'NOUCTRAN': 903, 'TRANIDONLY': 904,
}


def find_copy(name):
    for d in COPY_DIRS:
        for ext in ('.cpy', '.CPY', ''):
            p = os.path.join(d, name + ext)
            if os.path.isfile(p):
                return p
    raise FileNotFoundError(name)


def read_lines(path):
    with open(path, encoding='latin-1') as f:
        out = []
        for raw in f.read().splitlines():
            raw = raw.replace('\t', '    ')
            out.append(raw[:72].ljust(7) if len(raw) >= 7 else raw.ljust(7))
    return out


def mask_literals(text):
    return re.sub(r"'[^']*'|\"[^\"]*\"", lambda m: '#' * len(m.group(0)), text)


def is_comment(line):
    return len(line) > 6 and line[6] in '*/Dd'


def area(line):
    return line[7:72] if len(line) > 7 else ''


def tokenize_cmd(text):
    """Split 'VERB OPT(arg) OPT2 ...' into [(name, arg|None)]."""
    toks = []
    i, n = 0, len(text)
    while i < n:
        if text[i].isspace():
            i += 1
            continue
        m = re.match(r"[A-Za-z0-9\-]+", text[i:])
        if not m:
            raise ValueError('cannot tokenize: ' + text[i:])
        name = m.group(0).upper()
        i += len(name)
        j = i
        while j < n and text[j] == ' ':
            j += 1
        arg = None
        if j < n and text[j] == '(':
            depth, k, q = 0, j, None
            while k < n:
                c = text[k]
                if q:
                    if c == q:
                        q = None
                elif c in "'\"":
                    q = c
                elif c == '(':
                    depth += 1
                elif c == ')':
                    depth -= 1
                    if depth == 0:
                        break
                k += 1
            arg = text[j + 1:k].strip()
            i = k + 1
        toks.append((name, arg))
    return toks


def classify_arg(arg):
    a = arg.strip()
    m = re.fullmatch(r'DFH(RESP|VALUE)\s*\(\s*([A-Z0-9]+)\s*\)', a, re.I)
    if m:
        tbl = DFHRESP if m.group(1).upper() == 'RESP' else DFHVALUE
        return 'BY CONTENT', str(tbl[m.group(2).upper()])
    if re.match(r"^['\"]", a) or re.fullmatch(r'[+-]?\d+(\.\d+)?', a):
        return 'BY CONTENT', a
    if re.match(r'(?i)^LENGTH\s+OF\b', a) or re.search(r'\s[-+*/]\s', a):
        return 'BY CONTENT', a
    return 'BY REFERENCE', a


def wrap_args(head, items, indent=11):
    """Emit a CALL statement split over fixed-format lines."""
    lines = []
    cur = ' ' * indent + head
    for it in items:
        piece = ' ' + it
        if len(cur) + len(piece) > 71:
            lines.append(cur)
            cur = ' ' * (indent + 4) + it
            while len(cur) > 71:
                # split a long literal is not supported; long identifiers
                # never exceed the area in CBSA sources
                raise ValueError('argument too long: ' + it)
        else:
            cur += piece
    lines.append(cur)
    return lines


def replace_dfh_constants(code):
    def rep(m):
        tbl = DFHRESP if m.group(1).upper() == 'RESP' else DFHVALUE
        return str(tbl[m.group(2).upper()])
    return re.sub(r'(?i)DFH(RESP|VALUE)\s*\(\s*([A-Z0-9]+)\s*\)', rep, code)


class Translator:
    def __init__(self, prog):
        self.prog = prog
        self.meta = []
        self.cursors = {}
        self.ncmd = 0

    def next_id(self):
        self.ncmd += 1
        return self.ncmd

    # ------------------------------------------------------------- CICS
    def cics(self, text):
        toks = tokenize_cmd(text)
        verb = toks[0][0]
        opts = toks[1:]
        words = [verb]
        names = [o[0] for o in opts]
        # two-word verbs where the second word has no argument
        if verb in ('SYNCPOINT', 'BIF', 'SEND', 'RECEIVE', 'GET', 'PUT',
                    'FETCH', 'INQUIRE', 'SET', 'HANDLE', 'ASSIGN'):
            pass
        if verb in ('SEND', 'RECEIVE') and 'MAP' in names:
            mapname = dict(opts)['MAP'].strip("'\" ")
            if verb == 'SEND' and 'FROM' not in names and 'MAPONLY' not in names:
                opts.append(('FROM', mapname + 'O'))
            if verb == 'RECEIVE' and 'INTO' not in names:
                opts.append(('INTO', mapname + 'I'))
        cid = self.next_id()
        desc, args = [], []
        for name, arg in opts:
            if arg is None or arg == '' or name == 'LABEL':
                desc.append(name)
            else:
                desc.append(name + '=')
                by, a = classify_arg(arg)
                args.append(by + ' ' + a)
        self.meta.append('C %d %s %s' % (cid, verb, ' '.join(desc)))
        items = ["BY CONTENT '%s'" % self.prog.ljust(8), 'BY CONTENT %d' % cid]
        items += args
        out = wrap_args("CALL 'CBSACICS' USING", items)
        if verb == 'RETURN':
            out.append(' ' * 11 + 'GOBACK')
        return out

    # -------------------------------------------------------------- SQL
    def sql(self, text):
        t = ' '.join(text.split())
        u = t.upper()
        if u.startswith('DECLARE') and ' TABLE' in u.split('(')[0]:
            return []
        m = re.match(r'(?i)DECLARE\s+([A-Z0-9\-]+)\s+CURSOR\s+FOR\s+(.*)$', t)
        if m:
            sqltext, ins = self.hostvars(m.group(2))
            sqltext = re.sub(r'(?i)\s+FOR\s+FETCH\s+ONLY\s*$', '', sqltext)
            self.cursors[m.group(1).upper()] = (sqltext, ins)
            return []
        cid = self.next_id()
        if re.match(r'(?i)OPEN\s', t):
            cur = t.split()[1].upper()
            sqltext, ins = self.cursors[cur]
            self.meta.append('S %d OPEN %s %d 0 %s' % (cid, cur, len(ins), sqltext))
            return self.sqlcall(cid, ins, [])
        if re.match(r'(?i)CLOSE\s', t):
            cur = t.split()[1].upper()
            self.meta.append('S %d CLOSE %s 0 0 -' % (cid, cur))
            return self.sqlcall(cid, [], [])
        m = re.match(r'(?i)FETCH\s+(?:FROM\s+)?([A-Z0-9\-]+)\s+INTO\s+(.*)$', t)
        if m:
            outs = [h.strip()[1:] for h in m.group(2).split(',')]
            self.meta.append('S %d FETCH %s 0 %d -' % (cid, m.group(1).upper(), len(outs)))
            return self.sqlcall(cid, [], outs)
        if re.match(r'(?i)COMMIT', t):
            self.meta.append('S %d COMMIT - 0 0 -' % cid)
            return self.sqlcall(cid, [], [])
        if re.match(r'(?i)ROLLBACK', t):
            self.meta.append('S %d ROLLBACK - 0 0 -' % cid)
            return self.sqlcall(cid, [], [])
        outs = []
        m = re.match(r'(?i)(SELECT\s+.*?)\s+INTO\s+(.*?)\s+(FROM\s+.*)$', t)
        if m:
            outs = [h.strip()[1:] for h in m.group(2).split(',')]
            t = m.group(1) + ' ' + m.group(3)
        sqltext, ins = self.hostvars(t)
        kind = 'SELECT' if outs else 'EXEC'
        self.meta.append('S %d %s - %d %d %s' % (cid, kind, len(ins), len(outs), sqltext))
        return self.sqlcall(cid, ins, outs)

    @staticmethod
    def hostvars(t):
        ins = []

        def rep(m):
            ins.append(m.group(1))
            return '?'
        sqltext = re.sub(r':([A-Za-z0-9][A-Za-z0-9\-]*(?:\s+(?:OF|IN)\s+[A-Za-z0-9\-]+)?)', rep, t)
        return sqltext, ins

    def sqlcall(self, cid, ins, outs):
        items = ["BY CONTENT '%s'" % self.prog.ljust(8), 'BY CONTENT %d' % cid,
                 'BY REFERENCE SQLCA']
        items += ['BY REFERENCE ' + h for h in ins + outs]
        return wrap_args("CALL 'CBSASQL' USING", items)


def expand_include(name, depth=0):
    if name.upper() == 'SQLCA':
        return read_lines(find_copy('SQLCA'))
    return read_lines(find_copy(name))


def translate(src, outdir, batch=False):
    prog = os.path.splitext(os.path.basename(src))[0].upper()
    tr = Translator(prog)
    lines = ['      *CBSA-PP ' + l[7:].strip()
             if re.match(r'(?i)^\s*(CBL|PROCESS)(\s|$)', l[6:]) else l
             for l in read_lines(src)]

    # pass 1: inline EXEC SQL INCLUDE (copybooks may contain EXEC SQL)
    changed = True
    while changed:
        changed = False
        out = []
        i = 0
        while i < len(lines):
            ln = lines[i]
            if not is_comment(ln) and re.search(r'(?i)\bEXEC\s+SQL\b', mask_literals(area(ln))):
                buf = area(ln)
                j = i
                while not re.search(r'(?i)END-EXEC', buf):
                    j += 1
                    buf += ' ' + area(lines[j])
                if not re.search(r'(?i)EXEC\s+SQL\s+INCLUDE\b', buf):
                    out.append(ln)
                    i += 1
                    continue
                m = re.search(r'(?i)INCLUDE\s+([A-Z0-9]+)', buf)
                out.append('      *CBSA-PP INCLUDE ' + m.group(1))
                out.extend(expand_include(m.group(1)))
                i = j + 1
                changed = True
                continue
            out.append(ln)
            i += 1
        lines = out

    # pass 2: EXEC blocks
    out = []
    i = 0
    in_proc = False
    while i < len(lines):
        ln = lines[i]
        if is_comment(ln):
            out.append(ln)
            i += 1
            continue
        a = area(ln)
        if re.match(r'(?i)\s*PROCEDURE\s+DIVISION', a):
            in_proc = True
        m = re.search(r'(?i)\bEXEC\s+(CICS|SQL)\b', mask_literals(a))
        if not m:
            out.append(ln)
            i += 1
            continue
        prefix = a[:m.start()]
        kind = m.group(1).upper()
        buf = a[m.end():]
        j = i
        while not re.search(r'(?i)\bEND-EXEC\b', buf):
            j += 1
            if not is_comment(lines[j]):
                buf += ' ' + area(lines[j])
        e = re.search(r'(?i)\bEND-EXEC\b', buf)
        body, suffix = buf[:e.start()], buf[e.end():]
        if prefix.strip():
            out.append(' ' * 7 + prefix.rstrip())
        out.append('      *CBSA-PP EXEC %s %s' % (kind, ' '.join(body.split())[:50]))
        gen = tr.cics(body) if kind == 'CICS' else tr.sql(body)
        if suffix.strip() == '.' or suffix.strip().startswith('.'):
            if gen:
                gen[-1] += '.'
            elif in_proc:
                gen = [' ' * 11 + 'CONTINUE.']
            suffix = suffix.strip()[1:]
        elif not gen and in_proc:
            gen = [' ' * 11 + 'CONTINUE']
        out.extend(gen)
        if suffix.strip():
            out.append(' ' * 11 + suffix.strip())
        i = j + 1
    lines = out

    # pass 3: DFHRESP/DFHVALUE, FUNCTION RANDOM, PROCEDURE DIVISION, LINKAGE
    out = []
    need_rnd = False
    for idx, ln in enumerate(lines):
        if is_comment(ln):
            out.append(ln)
            continue
        code = replace_dfh_constants(area(ln))
        m = re.search(r'(?i)FUNCTION\s+RANDOM\s*(\(([^)]*)\))?', code)
        if m:
            need_rnd = True
            # find start of the COMPUTE statement containing it
            k = len(out)
            if not re.search(r'(?i)\bCOMPUTE\b', code[:m.start()]):
                k = len(out) - 1
                while not re.search(r'(?i)\bCOMPUTE\b', area(out[k])):
                    k -= 1
            if m.group(2) is not None:
                call = wrap_args("CALL 'CBSARNDS' USING",
                                 ['BY CONTENT ' + m.group(2).strip(),
                                  'BY REFERENCE CBSA-RND-VAL'])
            else:
                call = wrap_args("CALL 'CBSARND' USING", ['BY REFERENCE CBSA-RND-VAL'])
            if k == len(out):
                out.extend(call)
            else:
                out[k:k] = call
            code = code[:m.start()] + 'CBSA-RND-VAL' + code[m.end():]
        out.append((ln[:7] + code).rstrip())
    lines = out

    out = []
    has_linkage = any(re.match(r'(?i)\s*LINKAGE\s+SECTION', area(l)) for l in lines if not is_comment(l))
    has_comm = any(re.search(r'(?i)\b01\s+DFHCOMMAREA\b|\bBY\s+DFHCOMMAREA\b', area(l)) for l in lines if not is_comment(l))
    for ln in lines:
        a = area(ln) if not is_comment(ln) else ''
        if not batch and re.match(r'(?i)\s*PROCEDURE\s+DIVISION', a):
            if not has_linkage:
                out.append('       LINKAGE SECTION.')
                out.append('       COPY DFHEIBLK.')
                out.append('       01 DFHCOMMAREA PIC X(32767).')
            elif not has_comm:
                out.append('       01 DFHCOMMAREA PIC X(32767).')
            out.append('       PROCEDURE DIVISION USING DFHEIBLK DFHCOMMAREA.')
            continue
        out.append(ln)
        if not batch and has_linkage and re.match(r'(?i)\s*LINKAGE\s+SECTION', a):
            out.append('       COPY DFHEIBLK.')
        if need_rnd and re.match(r'(?i)\s*WORKING-STORAGE\s+SECTION', a):
            out.append('       01 CBSA-RND-VAL PIC V9(9) VALUE 0.')
    if batch:
        out = batch_fixups(out)

    os.makedirs(outdir, exist_ok=True)
    with open(os.path.join(outdir, prog + '_pp.cob'), 'w', encoding='latin-1') as f:
        f.write('\n'.join(out) + '\n')
    with open(os.path.join(outdir, prog + '.meta'), 'w', encoding='latin-1') as f:
        f.write('\n'.join(tr.meta) + '\n')
    return prog


def batch_fixups(lines):
    """BANKDATA: the KSDS CUSTOMER file is written sequentially in key order,
    so it is redirected to a fixed-length sequential file read back by the
    harness (record layout unchanged)."""
    out = []
    skip_cont = False
    for ln in lines:
        a = area(ln) if not is_comment(ln) else ''
        if re.search(r'(?i)ORGANIZATION\s+IS\s+INDEXED', a):
            ln = ln.replace('INDEXED', 'SEQUENTIAL').replace('indexed', 'SEQUENTIAL')
        elif re.search(r'(?i)^\s*(ACCESS\s+MODE|RECORD\s+KEY)', a):
            ln = '      *CBSA-PP ' + a.strip()
            if a.rstrip().endswith('.'):
                out.append('           .')
        out.append(ln)
    return out


if __name__ == '__main__':
    outdir = sys.argv[1]
    for s in sys.argv[2:]:
        print(translate(s, outdir, batch=os.path.basename(s).upper().startswith('BANKDATA')))
