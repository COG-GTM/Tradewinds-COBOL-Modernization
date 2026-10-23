"""Minimal COBOL copybook layout parser used by the harness to turn COMMAREA
bytes into named JSON fields and back (GnuCOBOL -std=ibm data formats:
DISPLAY with trailing overpunch sign, big-endian BINARY/COMP, COMP-3)."""
import os
import re
from decimal import Decimal

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
COPYDIR = os.path.join(ROOT, 'src', 'base', 'cobol_copy')


class Item:
    def __init__(self, level, name):
        self.level, self.name = level, name
        self.pic = None
        self.usage = 'DISPLAY'
        self.occurs = 1
        self.depending = None
        self.redefines = None
        self.children = []
        self.offset = 0
        self.size = 0
        self.digits = 0
        self.scale = 0
        self.signed = False
        self.alpha = False

    def elem_size(self):
        return self.size // self.occurs if self.occurs else self.size


def _expand_pic(p):
    return re.sub(r'(.)\((\d+)\)', lambda m: m.group(1) * int(m.group(2)), p.upper())


def _statements(text):
    lines = []
    for raw in text.splitlines():
        raw = raw.rstrip('\n')
        if len(raw) > 6 and raw[6] in '*/':
            continue
        lines.append(raw[7:72] if len(raw) > 7 else '')
    body = ' '.join(lines)
    return [s.strip() for s in re.split(r'\.(?:\s|$)', body) if s.strip()]


def parse(text):
    root = Item(0, '<root>')
    stack = [root]
    for st in _statements(text):
        toks = st.split()
        if not toks[0].isdigit():
            continue
        level = int(toks[0])
        if level in (66, 77, 88):
            continue
        name = toks[1] if len(toks) > 1 else 'FILLER'
        it = Item(level, name)
        i = 2
        while i < len(toks):
            t = toks[i].upper()
            if t in ('PIC', 'PICTURE'):
                i += 1
                if toks[i].upper() == 'IS':
                    i += 1
                it.pic = toks[i]
            elif t == 'REDEFINES':
                i += 1
                it.redefines = toks[i]
            elif t == 'OCCURS':
                i += 1
                n = int(toks[i])
                if i + 2 < len(toks) and toks[i + 1].upper() == 'TO':
                    n = int(toks[i + 2])
                    i += 2
                it.occurs = n
            elif t == 'DEPENDING':
                i += 1
                if toks[i].upper() == 'ON':
                    i += 1
                it.depending = toks[i]
            elif t in ('COMP', 'COMP-4', 'BINARY', 'COMP-5', 'COMPUTATIONAL'):
                it.usage = 'BINARY'
            elif t in ('COMP-3', 'PACKED-DECIMAL', 'COMPUTATIONAL-3'):
                it.usage = 'PACKED'
            elif t == 'POINTER':
                it.usage = 'POINTER'
            i += 1
        while stack[-1].level >= level:
            stack.pop()
        stack[-1].children.append(it)
        stack.append(it)
    if len(root.children) == 1 and root.children[0].level == 1 and root.children[0].children:
        root = root.children[0]
    _layout(root, 0)
    return root


def _layout(it, off):
    it.offset = off
    if it.children:
        pos = off
        sizes = {}
        for c in it.children:
            if c.redefines and c.redefines in sizes:
                _layout(c, sizes[c.redefines][0])
                continue
            _layout(c, pos)
            sizes[c.name] = (pos, c.size)
            pos += c.size
        unit = pos - off
    elif it.usage == 'POINTER':
        unit = 8
    else:
        p = _expand_pic(it.pic or 'X')
        it.signed = p.startswith('S')
        p = p.lstrip('S')
        it.alpha = 'X' in p or 'A' in p
        if 'V' in p:
            ip, fp = p.split('V', 1)
        else:
            ip, fp = p, ''
        it.digits = ip.count('9') + fp.count('9')
        it.scale = fp.count('9')
        if it.alpha:
            unit = len(p)
        elif it.usage == 'BINARY':
            unit = 2 if it.digits <= 4 else 4 if it.digits <= 9 else 8
        elif it.usage == 'PACKED':
            unit = it.digits // 2 + 1
        else:
            unit = len(p.replace('V', ''))
    it.size = unit * it.occurs
    return it.size


OVERPUNCH_NEG = {chr(0x70 + d): d for d in range(10)}
OVERPUNCH_NEG.update({'}': 0, **{chr(ord('J') + d - 1): d for d in range(1, 10)}})
OVERPUNCH_POS = {'{': 0, **{chr(ord('A') + d - 1): d for d in range(1, 10)}}


def _num(v, scale):
    if scale:
        return float(Decimal(v).scaleb(-scale).quantize(Decimal(1).scaleb(-scale)))
    return v


def decode_elem(it, b):
    if it.usage == 'POINTER':
        return None
    if it.alpha or it.pic is None:
        return b.decode('latin-1').rstrip(' \x00')
    if it.usage == 'BINARY':
        return _num(int.from_bytes(b, 'big', signed=True), it.scale)
    if it.usage == 'PACKED':
        h = b.hex()
        try:
            v = int(h[:-1])
        except ValueError:
            return 'X' + h
        return _num(-v if h[-1] in 'db' else v, it.scale)
    s = b.decode('latin-1')
    neg = False
    if it.signed and s:
        c = s[-1]
        if c in OVERPUNCH_NEG:
            s, neg = s[:-1] + str(OVERPUNCH_NEG[c]), True
        elif c in OVERPUNCH_POS:
            s = s[:-1] + str(OVERPUNCH_POS[c])
    if not s.isdigit():
        return s.rstrip(' \x00') if s.strip(' \x00') else (s.rstrip('\x00') and s or '')
    v = int(s)
    return _num(-v if neg else v, it.scale)


def encode_elem(it, val, size):
    if it.usage == 'POINTER':
        return b'\x00' * size
    if val is None:
        val = '' if it.alpha else 0
    if it.alpha or it.pic is None or isinstance(val, str) and not re.fullmatch(r'-?\d+(\.\d+)?', val):
        return str(val).encode('latin-1')[:size].ljust(size, b' ')
    d = Decimal(str(val)).scaleb(it.scale)
    v = int(d.to_integral_value())
    if it.usage == 'BINARY':
        return v.to_bytes(size, 'big', signed=True)
    if it.usage == 'PACKED':
        digs = str(abs(v)).rjust(size * 2 - 1, '0')[-(size * 2 - 1):]
        return bytes.fromhex(digs + ('d' if v < 0 else 'c' if it.signed else 'f'))
    s = str(abs(v)).rjust(size, '0')[-size:]
    if it.signed and v < 0:
        s = s[:-1] + chr(0x70 + int(s[-1]))
    return s.encode('latin-1')


def decode(it, data, base=0, ctx=None):
    """Return {name: value} for every non-FILLER, non-REDEFINES item."""
    out = {}
    ctx = {} if ctx is None else ctx
    for c in it.children:
        if c.redefines or c.name.upper() == 'FILLER' and not c.children:
            continue
        count = c.occurs
        if c.depending is not None and isinstance(ctx.get(c.depending), int):
            count = max(0, min(c.occurs, ctx[c.depending]))
        es = c.elem_size()
        vals = []
        for k in range(count if c.occurs > 1 else 1):
            o = base + c.offset - it.offset + k * es
            if c.children:
                vals.append(decode(c, data, o, ctx))
            else:
                vals.append(decode_elem(c, data[o:o + es]))
        v = vals if c.occurs > 1 else vals[0]
        if c.children and c.occurs == 1 and c.name.upper() == 'FILLER':
            out.update(v)
        else:
            out[c.name] = v
        if not c.children:
            ctx[c.name] = v
    return out


def encode(it, values, buf=None, base=0):
    if buf is None:
        buf = bytearray(b' ' * it.size)
        _zero_numeric(it, buf, 0)
    known = set()
    for c in it.children:
        known.add(c.name)
        if c.name.upper() == 'FILLER' and c.children:
            known.update(x.name for x in c.children)
    unknown = set(values) - known
    if unknown:
        raise KeyError('unknown COMMAREA field(s) %s under %s' % (sorted(unknown), it.name))
    for c in it.children:
        if c.redefines:
            continue
        if c.name not in values and not (c.name.upper() == 'FILLER' and c.children):
            continue
        es = c.elem_size()
        v = values if c.name.upper() == 'FILLER' else values[c.name]
        items = v if c.occurs > 1 else [v]
        for k, ev in enumerate(items[:c.occurs]):
            o = base + c.offset - it.offset + k * es
            if c.children:
                encode(c, ev, buf, o)
            else:
                buf[o:o + es] = encode_elem(c, ev, es)
    return buf


def _zero_numeric(it, buf, base):
    for c in it.children:
        if c.redefines:
            continue
        es = c.elem_size()
        for k in range(c.occurs):
            o = base + c.offset - it.offset + k * es
            if c.children:
                _zero_numeric(c, buf, o)
            elif not c.alpha and c.pic is not None or c.usage == 'POINTER':
                buf[o:o + es] = encode_elem(c, 0, es)


def load(name):
    return parse(open(os.path.join(COPYDIR, name + '.cpy'), encoding='latin-1').read())
