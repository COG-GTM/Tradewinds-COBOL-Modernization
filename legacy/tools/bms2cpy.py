#!/usr/bin/env python3
"""Generate BMS symbolic-map copybooks (as DFHMAPS TYPE=DSECT would, with
EXTATT=YES/TIOAPFX=YES, same shape as src/base/cobol_copy/BNK1DDM.cpy) and a
field-layout file <out>/<MAP>.map used by the runtime to render SEND MAP /
fill RECEIVE MAP as JSON map fields."""
import os
import re
import sys


def statements(path):
    stmt = ''
    for raw in open(path, encoding='latin-1').read().splitlines():
        if raw.startswith('*'):
            continue
        body = raw[:71]
        cont = len(raw) > 71 and raw[71] != ' '
        stmt += body.rstrip() if not stmt else body.strip()
        if not cont:
            if stmt.strip():
                yield stmt
            stmt = ''


def parse_ops(s):
    ops, cur, depth, q = {}, '', 0, False
    parts = []
    for c in s:
        if c == "'" :
            q = not q
        if not q and c == '(':
            depth += 1
        if not q and c == ')':
            depth -= 1
        if c == ',' and depth == 0 and not q:
            parts.append(cur)
            cur = ''
        else:
            cur += c
    parts.append(cur)
    for p in parts:
        if '=' in p:
            k, v = p.split('=', 1)
            ops[k.strip().upper()] = v.strip()
    return ops


def convert(path, outdir):
    mapset, maps, cur = None, [], None
    for st in statements(path):
        m = re.match(r'^(\S*)\s+(DFHMSD|DFHMDI|DFHMDF)\s*(.*)$', st)
        if not m:
            continue
        label, macro, rest = m.group(1), m.group(2), m.group(3)
        if macro == 'DFHMSD':
            if mapset is None:
                mapset = label
            continue
        ops = parse_ops(rest)
        if macro == 'DFHMDI':
            cur = {'name': label, 'fields': []}
            maps.append(cur)
        elif label:
            cur['fields'].append({
                'name': label, 'len': int(ops.get('LENGTH', '1')),
                'occurs': int(ops.get('OCCURS', '1')),
                'picout': ops.get('PICOUT', '').strip("'"),
                'pos': ops.get('POS', '')})
    cpy = []
    for mp in maps:
        n = mp['name']
        cpy.append('       01  %sI.' % n)
        cpy.append('           02  FILLER PIC X(12).')
        layout, off = [], 12
        for f in mp['fields']:
            lvl, ind = '02', ''
            if f['occurs'] > 1:
                cpy.append('           02  %sD OCCURS %d TIMES.' % (f['name'], f['occurs']))
                lvl, ind = '03', '  '
            cpy.append('         %s %s  %sL    COMP  PIC  S9(4).' % (ind, lvl, f['name']))
            cpy.append('         %s %s  %sF    PICTURE X.' % (ind, lvl, f['name']))
            cpy.append('         %s %s  FILLER REDEFINES %sF.' % (ind, lvl, f['name']))
            cpy.append('         %s   %02d %sA    PICTURE X.' % (ind, int(lvl) + 1, f['name']))
            cpy.append('         %s %s  FILLER   PICTURE X(6).' % (ind, lvl))
            cpy.append('         %s %s  %sI  PIC X(%d).' % (ind, lvl, f['name'], f['len']))
            for k in range(f['occurs']):
                layout.append((f['name'] + ('' if f['occurs'] == 1 else '(%d)' % (k + 1)),
                               off, off + 9, f['len']))
                off += 9 + f['len']
        cpy.append('       01  %sO REDEFINES %sI.' % (n, n))
        cpy.append('           02  FILLER PIC X(12).')
        for f in mp['fields']:
            lvl, ind = '02', ''
            if f['occurs'] > 1:
                cpy.append('           02  %sR OCCURS %d TIMES.' % (f['name'], f['occurs']))
                lvl, ind = '03', '  '
            cpy.append('         %s %s  FILLER PICTURE X(3).' % (ind, lvl))
            for a in 'CPHVUM':
                cpy.append('         %s %s  %s%s    PICTURE X.' % (ind, lvl, f['name'], a))
            pic = f['picout'] if f['picout'] else 'X(%d)' % f['len']
            cpy.append('         %s %s  %sO  PIC %s.' % (ind, lvl, f['name'], pic))
        with open(os.path.join(outdir, n + '.map'), 'w') as fh:
            fh.write('MAP %s %s %d\n' % (n, mapset, off))
            for name, loff, doff, ln in layout:
                # name, offset of L field, offset of data, data length
                fh.write('%s %d %d %d\n' % (name, loff, doff, ln))
    with open(os.path.join(outdir, mapset + '.cpy'), 'w') as fh:
        fh.write('\n'.join(cpy) + '\n')
    return mapset, [m['name'] for m in maps]


if __name__ == '__main__':
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for p in sys.argv[2:]:
        print(*convert(p, out))
