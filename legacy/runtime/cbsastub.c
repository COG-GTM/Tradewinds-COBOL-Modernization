/*
 * CBSA legacy stub runtime for GnuCOBOL.
 *
 * Provides the CICS / DB2 / Language Environment services that the
 * translated CBSA programs call (see legacy/tools/cbsa_pp.py), backed by a
 * SQLite database seeded from baseline/seed/seed.sql.  Also contains the
 * driver (main) that runs one CICS task, one terminal task or BANKDATA.
 *
 *   cbsarun <db> link  <PROG> <commarea-in> <commarea-out> <events>
 *   cbsarun <db> term  <PROG> <commarea-in> <commarea-out> <events>
 *   cbsarun <db> batch <PROG> <parm-string> <unused>       <events>
 *
 * env: CBSA_LIB (module dir), CBSA_FIXED_TIME (ISO-8601),
 *      CBSA_RANDOM_SEED, CBSA_TASKN, CBSA_AID, CBSA_MAPIN, CBSA_TRANSID,
 *      CBSA_TERMID.
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <libcob.h>
#include <sqlite3.h>

/* ------------------------------------------------------------ globals */
static sqlite3 *db;
static FILE *ev;
static const char *libdir;
static unsigned char *top_commarea;
static int top_calen;
static const char *out_path;
static int64_t fixed_epoch;          /* seconds since 1970 UTC */
static int fixed_ms;
static int64_t rnd_base;
static uint64_t rnd_state;
static char cur_prog[9] = "";

#define EIBSZ 85
typedef struct task {
    unsigned char eib[EIBSZ];
    char transid[5];
    long taskn;
    double vtime;              /* virtual seconds elapsed (DELAY) */
    char prog[9];
} task_t;
static task_t *cur_task;

/* channels / containers ------------------------------------------- */
typedef struct cont { char chan[17], name[17]; int owner; unsigned char *data; int len; struct cont *next; } cont_t;
static cont_t *conts;
typedef struct child { char token[17], chan[17], transid[5]; int owner_id; int id; double done_at; int fetched; char abcode[5]; int compst; } child_t;
static child_t children[64];
static int nchildren;
static int chan_owner = 0;       /* 0 = top task, k = child k */

/* ------------------------------------------------------------- utils */
static void evf(const char *fmt, ...) {
    if (!ev) return;
    va_list ap; va_start(ap, fmt); vfprintf(ev, fmt, ap); va_end(ap);
    fputc('\n', ev); fflush(ev);
}
static void rtrim_copy(char *dst, const unsigned char *src, int n) {
    memcpy(dst, src, n); dst[n] = 0;
    while (n > 0 && (dst[n - 1] == ' ' || dst[n - 1] == 0)) dst[--n] = 0;
}
static void put_be(unsigned char *p, int n, int64_t v) {
    for (int i = n - 1; i >= 0; i--) { p[i] = v & 0xff; v >>= 8; }
}
static int64_t get_be(const unsigned char *p, int n) {
    int64_t v = (p[0] & 0x80) ? -1 : 0;
    for (int i = 0; i < n; i++) v = (v << 8) | p[i];
    return v;
}
static void put_packed(unsigned char *p, int n, int64_t v) {
    int neg = v < 0; if (neg) v = -v;
    memset(p, 0, n);
    p[n - 1] = (unsigned char)(((v % 10) << 4) | (neg ? 0x0d : 0x0c)); v /= 10;
    for (int i = n - 2; i >= 0; i--) { p[i] = (unsigned char)((v % 10) | (((v / 10) % 10) << 4)); v /= 100; }
}

/* typed field access through a numeric-display temp + cob_move */
static void field_set_dec(cob_field *f, const char *dec) {
    char buf[64]; int neg = 0; const char *s = dec;
    while (*s == ' ') s++;
    if (*s == '-') { neg = 1; s++; } else if (*s == '+') s++;
    char ip[40] = "", fp[20] = ""; const char *dot = strchr(s, '.');
    if (dot) { snprintf(ip, sizeof ip, "%.*s", (int)(dot - s), s); snprintf(fp, sizeof fp, "%s", dot + 1); }
    else snprintf(ip, sizeof ip, "%s", s);
    int scale = 9; char fr[10]; memset(fr, '0', 9); fr[9] = 0;
    for (int i = 0; i < 9 && fp[i] && isdigit((unsigned char)fp[i]); i++) fr[i] = fp[i];
    char in[19]; memset(in, '0', 18); in[18] = 0;
    int l = (int)strlen(ip); for (int i = 0; i < l && i < 18; i++) in[17 - i] = ip[l - 1 - i];
    snprintf(buf, sizeof buf, "%c%s%s", neg ? '-' : '+', in, fr);
    cob_field_attr a = {COB_TYPE_NUMERIC_DISPLAY, 27, scale, COB_FLAG_HAVE_SIGN | COB_FLAG_SIGN_SEPARATE | COB_FLAG_SIGN_LEADING, NULL};
    cob_field t = {28, (unsigned char *)buf, &a};
    if (f->attr->type == COB_TYPE_ALPHANUMERIC || f->attr->type == COB_TYPE_GROUP) {
        /* alphanumeric receiver: right-justified digits */
        char d[64]; snprintf(d, sizeof d, "%s", s);
        int n = (int)f->size, dl = (int)strlen(d);
        memset(f->data, '0', n);
        for (int i = 0; i < n && i < dl; i++) f->data[n - 1 - i] = d[dl - 1 - i];
        return;
    }
    cob_move(&t, f);
}
static void field_set_int(cob_field *f, int64_t v) { char b[32]; snprintf(b, sizeof b, "%lld", (long long)v); field_set_dec(f, b); }
static void field_get_dec(cob_field *f, char *out, int outsz, int *scale_out) {
    if (f->attr->type == COB_TYPE_ALPHANUMERIC || f->attr->type == COB_TYPE_GROUP) {
        char tmp[300]; rtrim_copy(tmp, f->data, f->size > 290 ? 290 : (int)f->size);
        snprintf(out, outsz, "%s", tmp); if (scale_out) *scale_out = -1; return;
    }
    char buf[29];
    cob_field_attr a = {COB_TYPE_NUMERIC_DISPLAY, 27, 9, COB_FLAG_HAVE_SIGN | COB_FLAG_SIGN_SEPARATE | COB_FLAG_SIGN_LEADING, NULL};
    cob_field t = {28, (unsigned char *)buf, &a};
    cob_move(f, &t);
    buf[28] = 0;
    int sc = f->attr->scale > 0 ? f->attr->scale : 0;
    char ip[19]; memcpy(ip, buf + 1, 18); ip[18] = 0;
    char *p = ip; while (*p == '0' && p[1]) p++;
    if (sc) snprintf(out, outsz, "%s%s.%.*s", buf[0] == '-' ? "-" : "", p, sc, buf + 19);
    else snprintf(out, outsz, "%s%s", buf[0] == '-' ? "-" : "", p);
    if (scale_out) *scale_out = sc;
}
static int64_t field_get_int(cob_field *f) { char b[64]; field_get_dec(f, b, sizeof b, NULL); return atoll(b); }
static void field_set_str(cob_field *f, const char *s, int n) {
    int l = n < 0 ? (int)strlen(s) : n;
    memset(f->data, ' ', f->size);
    memcpy(f->data, s, l > (int)f->size ? f->size : (size_t)l);
}
static void field_get_str(cob_field *f, char *out, int max) {
    int n = (int)f->size < max - 1 ? (int)f->size : max - 1; rtrim_copy(out, f->data, n);
}
static int field_is_num(cob_field *f) {
    int t = f->attr->type; return t == COB_TYPE_NUMERIC_BINARY || t == COB_TYPE_NUMERIC_PACKED || t == COB_TYPE_NUMERIC_DISPLAY;
}

/* ------------------------------------------------------- time / random */
static void parse_fixed_time(void) {
    const char *s = getenv("CBSA_FIXED_TIME");
    if (!s || !*s) s = "2026-01-15T10:30:00Z";
    int Y, M, D, h = 0, m = 0, sec = 0;
    sscanf(s, "%d-%d-%dT%d:%d:%d", &Y, &M, &D, &h, &m, &sec);
    struct tm tm = {0}; tm.tm_year = Y - 1900; tm.tm_mon = M - 1; tm.tm_mday = D;
    tm.tm_hour = h; tm.tm_min = m; tm.tm_sec = sec;
    fixed_epoch = timegm(&tm); fixed_ms = 0;
}
static void now_tm(struct tm *tm, double extra) { time_t t = (time_t)(fixed_epoch + (int64_t)extra); gmtime_r(&t, tm); }
static double task_vtime(void) { return cur_task ? cur_task->vtime : 0; }

/* java.util.Random LCG */
static void rnd_seed(int64_t s) { rnd_state = ((uint64_t)s ^ 0x5DEECE66DULL) & ((1ULL << 48) - 1); }
static int rnd_next(int bits) {
    rnd_state = (rnd_state * 0x5DEECE66DULL + 0xBULL) & ((1ULL << 48) - 1);
    return (int)(int64_t)(rnd_state >> (48 - bits));
}
static double rnd_double(void) {
    return (double)(((int64_t)(rnd_next(26)) << 27) + rnd_next(27)) * (1.0 / (double)(1ULL << 53));
}
static void rnd_out(cob_field *f) {
    double d = rnd_double();
    int64_t n = (int64_t)(d * 1000000000.0);
    char b[32]; snprintf(b, sizeof b, "0.%09lld", (long long)n);
    field_set_dec(f, b);
}
int CBSARNDS(void *a, void *b) {
    (void)a; (void)b;
    cob_field *s = cob_get_param_field(1, "CBSARNDS");
    cob_field *o = cob_get_param_field(2, "CBSARNDS");
    rnd_seed(rnd_base + field_get_int(s));
    rnd_out(o);
    return 0;
}
int CBSARND(void *a) { (void)a; rnd_out(cob_get_param_field(1, "CBSARND")); return 0; }

/* --------------------------------------------------------------- abend */
static void finish_and_exit(int code);
static void do_abend(const char *code) {
    char c[5]; snprintf(c, sizeof c, "%-4.4s", code);
    evf("ABEND\t%s\t%s", c, cur_prog);
    sqlite3_exec(db, "ROLLBACK", 0, 0, 0);
    finish_and_exit(0);
}
static void sql_or_die(const char *sql) {
    char *err = 0;
    if (sqlite3_exec(db, sql, 0, 0, &err) != SQLITE_OK) { fprintf(stderr, "sqlite: %s: %s\n", sql, err); exit(3); }
}
static void finish_and_exit(int code) {
    if (out_path && top_commarea) {
        FILE *f = fopen(out_path, "wb");
        if (f) { fwrite(top_commarea, 1, top_calen, f); fclose(f); }
    }
    if (ev) fclose(ev);
    sqlite3_close(db);
    exit(code);
}

/* ----------------------------------------------------------- metadata */
typedef struct meta { char prog[9]; int id; char kind; char verb[16]; char opts[40][16]; int optarg[40]; int nopts;
                      char sqlkind[10]; char cursor[32]; int nin, nout; char *sql; struct meta *next; } meta_t;
static meta_t *metas;
static meta_t *get_meta(const char *prog, int id) {
    for (meta_t *m = metas; m; m = m->next) if (m->id == id && !strcmp(m->prog, prog)) return m;
    char path[1024]; snprintf(path, sizeof path, "%s/%s.meta", libdir, prog);
    FILE *f = fopen(path, "r"); if (!f) { fprintf(stderr, "no meta %s\n", path); exit(4); }
    char line[8192];
    while (fgets(line, sizeof line, f)) {
        line[strcspn(line, "\n")] = 0;
        if (!line[0]) continue;
        meta_t *m = calloc(1, sizeof *m); strcpy(m->prog, prog);
        m->kind = line[0];
        char *save, *tok = strtok_r(line + 2, " ", &save);
        m->id = atoi(tok);
        if (m->kind == 'C') {
            tok = strtok_r(NULL, " ", &save); snprintf(m->verb, sizeof m->verb, "%s", tok);
            while ((tok = strtok_r(NULL, " ", &save))) {
                int l = (int)strlen(tok), hasarg = tok[l - 1] == '=';
                if (hasarg) tok[l - 1] = 0;
                snprintf(m->opts[m->nopts], 16, "%s", tok); m->optarg[m->nopts++] = hasarg;
            }
        } else {
            snprintf(m->sqlkind, sizeof m->sqlkind, "%s", strtok_r(NULL, " ", &save));
            snprintf(m->cursor, sizeof m->cursor, "%s", strtok_r(NULL, " ", &save));
            m->nin = atoi(strtok_r(NULL, " ", &save));
            m->nout = atoi(strtok_r(NULL, " ", &save));
            m->sql = strdup(save ? save : "");
        }
        m->next = metas; metas = m;
    }
    fclose(f);
    for (meta_t *m = metas; m; m = m->next) if (m->id == id && !strcmp(m->prog, prog)) return m;
    fprintf(stderr, "no meta %s %d\n", prog, id); exit(4);
}

/* command context */
typedef struct { meta_t *m; const char *fn; cob_field *p[64]; int np; } cmd_t;
/* Parameters are captured on entry: a nested LINK/RUN resets libcob's
   current-call parameter list before RESP/RESP2 etc. are stored. */
static void cmd_capture(cmd_t *c) {
    c->np = cob_get_num_params(); if (c->np > 63) c->np = 63;
    for (int i = 1; i <= c->np; i++) c->p[i] = cob_get_param_field(i, c->fn);
}
static int has(cmd_t *c, const char *o) { for (int i = 0; i < c->m->nopts; i++) if (!strcmp(c->m->opts[i], o)) return 1; return 0; }
static cob_field *opt(cmd_t *c, const char *o) {
    int p = 3;
    for (int i = 0; i < c->m->nopts; i++) {
        if (c->m->optarg[i]) { if (!strcmp(c->m->opts[i], o)) return p <= c->np ? c->p[p] : NULL; p++; }
    }
    return NULL;
}
static void opt_str(cmd_t *c, const char *o, char *out, int max) {
    cob_field *f = opt(c, o); out[0] = 0; if (!f) return;
    if (field_is_num(f)) { char b[64]; field_get_dec(f, b, sizeof b, NULL); snprintf(out, max, "%s", b); }
    else field_get_str(f, out, max);
}
static int64_t opt_int(cmd_t *c, const char *o, int64_t dflt) { cob_field *f = opt(c, o); return f ? field_get_int(f) : dflt; }

/* ---------------------------------------------------------------- EIB */
static void eib_init(task_t *t, const char *transid, long taskn, int calen, char aid) {
    memset(t->eib, 0, EIBSZ);
    struct tm tm; now_tm(&tm, 0);
    put_packed(t->eib + 0, 4, tm.tm_hour * 10000 + tm.tm_min * 100 + tm.tm_sec);
    put_packed(t->eib + 4, 4, (tm.tm_year / 100) * 100000 + (tm.tm_year % 100) * 1000 + tm.tm_yday + 1);
    memset(t->eib + 8, ' ', 4); memcpy(t->eib + 8, transid, strlen(transid) > 4 ? 4 : strlen(transid));
    put_packed(t->eib + 12, 4, taskn);
    const char *term = getenv("CBSA_TERMID"); if (!term) term = "T001";
    memcpy(t->eib + 16, term, 4);
    put_be(t->eib + 24, 2, calen);
    t->eib[26] = aid ? aid : '\'';
    memset(t->eib + 29, 0, 6); memset(t->eib + 35, ' ', 24);
    snprintf(t->transid, 5, "%s", transid); t->taskn = taskn;
}
static void set_resp(cmd_t *c, int resp, int resp2) {
    if (cur_task) { put_be(cur_task->eib + 76, 4, resp); put_be(cur_task->eib + 80, 4, resp2); }
    cob_field *r = opt(c, "RESP"), *r2 = opt(c, "RESP2");
    if (r) field_set_int(r, resp);
    if (r2) field_set_int(r2, resp2);
    if (resp && !r && !has(c, "NOHANDLE")) {
        const char *ab = "AEI?";
        switch (resp) { case 13: ab = "AEIM"; break; case 14: ab = "AEIN"; break; case 16: ab = "AEIP"; break;
                        case 22: ab = "AEIV"; break; case 27: ab = "AEI0"; break; case 12: ab = "AEIL"; break;
                        case 20: ab = "AEIT"; break; case 36: ab = "MAPF"; break; case 110: ab = "AEYF"; break; }
        evf("UNHANDLED_RESP\t%s\t%s\t%d\t%d", cur_prog, c->m->verb, resp, resp2);
        do_abend(ab);
    }
}

/* ----------------------------------------------------- program calls */
static int prog_exists(const char *name) {
    char p[1024]; snprintf(p, sizeof p, "%s/%s.so", libdir, name);
    FILE *f = fopen(p, "r"); if (f) { fclose(f); return 1; } return 0;
}
static int call_prog(const char *name, unsigned char *commarea, int calen) {
    char save[9]; strcpy(save, cur_prog);
    snprintf(cur_prog, sizeof cur_prog, "%s", name);
    unsigned char *ca = calloc(1, 32768);
    if (commarea && calen > 0) memcpy(ca, commarea, calen);
    int64_t savecalen = get_be(cur_task->eib + 24, 2);
    put_be(cur_task->eib + 24, 2, calen);
    void *argv[2] = {cur_task->eib, ca};
    evf("LINK\t%s\t%d", name, calen);
    cob_call(name, 2, argv);
    cob_cancel(name);
    if (commarea && calen > 0) memcpy(commarea, ca, calen);
    free(ca);
    put_be(cur_task->eib + 24, 2, savecalen);
    strcpy(cur_prog, save);
    return 0;
}

/* -------------------------------------------------- CUSTOMER (VSAM) */
#define CUSTLEN 259
typedef struct { char sc[7], no[11]; } ckey_t;
static void key_from(const unsigned char *k, ckey_t *o) { memcpy(o->sc, k, 6); o->sc[6] = 0; memcpy(o->no, k + 6, 10); o->no[10] = 0; }
static void iso_to_ddmmyyyy(const char *iso, char *out) {
    if (iso && strlen(iso) >= 10 && iso[4] == '-') { memcpy(out, iso + 8, 2); memcpy(out + 2, iso + 5, 2); memcpy(out + 4, iso, 4); }
    else memcpy(out, "00000000", 8);
}
static void ddmmyyyy_to_iso(const unsigned char *d, char *out) { snprintf(out, 11, "%.4s-%.2s-%.2s", d + 4, d + 2, d); }
static int64_t control_num(const char *name) {
    sqlite3_stmt *st; int64_t v = 0;
    sqlite3_prepare_v2(db, "SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME=?", -1, &st, 0);
    sqlite3_bind_text(st, 1, name, -1, SQLITE_TRANSIENT);
    if (sqlite3_step(st) == SQLITE_ROW) v = sqlite3_column_int64(st, 0);
    sqlite3_finalize(st); return v;
}
static void control_set(const char *name, int64_t v) {
    sqlite3_stmt *st;
    sqlite3_prepare_v2(db, "INSERT INTO CONTROL(CONTROL_NAME,CONTROL_VALUE_NUM,CONTROL_VALUE_STR) VALUES(?,?,'') "
                           "ON CONFLICT(CONTROL_NAME) DO UPDATE SET CONTROL_VALUE_NUM=excluded.CONTROL_VALUE_NUM", -1, &st, 0);
    sqlite3_bind_text(st, 1, name, -1, SQLITE_TRANSIENT); sqlite3_bind_int64(st, 2, v);
    sqlite3_step(st); sqlite3_finalize(st);
}
static const char *SORTCODE = "987654";
static int is_ctrl_key(ckey_t *k) { return !strcmp(k->sc, "000000") && !strcmp(k->no, "9999999999"); }
static void ctrl_record(unsigned char *r) {
    char b[64]; memset(r, ' ', CUSTLEN);
    memcpy(r, "CTRL", 4); memcpy(r + 4, "000000", 6); memcpy(r + 10, "9999999999", 10);
    snprintf(b, sizeof b, "%010lld", (long long)control_num("987654-CUSTOMER-COUNT")); memcpy(r + 20, b, 10);
    snprintf(b, sizeof b, "%010lld", (long long)control_num("987654-CUSTOMER-LAST")); memcpy(r + 30, b, 10);
    memset(r + 240, '0', 19);
}
static int cust_row_record(sqlite3_stmt *st, unsigned char *r) {
    memset(r, ' ', CUSTLEN);
    const char *eye = (const char *)sqlite3_column_text(st, 0);
    memcpy(r, eye ? eye : "CUST", 4);
    memcpy(r + 4, sqlite3_column_text(st, 1), 6);
    memcpy(r + 10, sqlite3_column_text(st, 2), 10);
    const char *nm = (const char *)sqlite3_column_text(st, 3); if (nm) memcpy(r + 20, nm, strlen(nm) > 60 ? 60 : strlen(nm));
    const char *ad = (const char *)sqlite3_column_text(st, 4); if (ad) memcpy(r + 80, ad, strlen(ad) > 160 ? 160 : strlen(ad));
    char d[9]; iso_to_ddmmyyyy((const char *)sqlite3_column_text(st, 5), d); memcpy(r + 240, d, 8);
    char cs[8]; snprintf(cs, sizeof cs, "%03d", sqlite3_column_int(st, 6)); memcpy(r + 248, cs, 3);
    iso_to_ddmmyyyy((const char *)sqlite3_column_text(st, 7), d); memcpy(r + 251, d, 8);
    return 0;
}
#define CUST_COLS "CUSTOMER_EYECATCHER,CUSTOMER_SORTCODE,CUSTOMER_NUMBER,CUSTOMER_NAME,CUSTOMER_ADDRESS,CUSTOMER_DATE_OF_BIRTH,CUSTOMER_CREDIT_SCORE,CUSTOMER_CS_REVIEW_DATE"
static int cust_read(ckey_t *k, unsigned char *r) {
    if (is_ctrl_key(k)) { ctrl_record(r); return 1; }
    sqlite3_stmt *st; int found = 0;
    sqlite3_prepare_v2(db, "SELECT " CUST_COLS " FROM CUSTOMER WHERE CUSTOMER_SORTCODE=? AND CUSTOMER_NUMBER=?", -1, &st, 0);
    sqlite3_bind_text(st, 1, k->sc, -1, SQLITE_TRANSIENT); sqlite3_bind_text(st, 2, k->no, -1, SQLITE_TRANSIENT);
    if (sqlite3_step(st) == SQLITE_ROW) { cust_row_record(st, r); found = 1; }
    sqlite3_finalize(st); return found;
}
static int cust_store(const unsigned char *r, int insert) {
    ckey_t k; key_from(r + 4, &k);
    if (is_ctrl_key(&k)) {
        char b[11];
        memcpy(b, r + 20, 10); b[10] = 0; control_set("987654-CUSTOMER-COUNT", atoll(b));
        memcpy(b, r + 30, 10); b[10] = 0; control_set("987654-CUSTOMER-LAST", atoll(b));
        return 0;
    }
    sqlite3_stmt *st;
    sqlite3_prepare_v2(db, insert ? "INSERT INTO CUSTOMER(" CUST_COLS ") VALUES(?,?,?,?,?,?,?,?)"
                                  : "UPDATE CUSTOMER SET CUSTOMER_EYECATCHER=?1,CUSTOMER_NAME=?4,CUSTOMER_ADDRESS=?5,CUSTOMER_DATE_OF_BIRTH=?6,"
                                    "CUSTOMER_CREDIT_SCORE=?7,CUSTOMER_CS_REVIEW_DATE=?8 WHERE CUSTOMER_SORTCODE=?2 AND CUSTOMER_NUMBER=?3", -1, &st, 0);
    char t[161], d1[11], d2[11], cs[4];
    rtrim_copy(t, r, 4); sqlite3_bind_text(st, 1, t, -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(st, 2, k.sc, -1, SQLITE_TRANSIENT); sqlite3_bind_text(st, 3, k.no, -1, SQLITE_TRANSIENT);
    rtrim_copy(t, r + 20, 60); sqlite3_bind_text(st, 4, t, -1, SQLITE_TRANSIENT);
    rtrim_copy(t, r + 80, 160); sqlite3_bind_text(st, 5, t, -1, SQLITE_TRANSIENT);
    ddmmyyyy_to_iso(r + 240, d1); sqlite3_bind_text(st, 6, d1, -1, SQLITE_TRANSIENT);
    memcpy(cs, r + 248, 3); cs[3] = 0; sqlite3_bind_int(st, 7, atoi(cs));
    ddmmyyyy_to_iso(r + 251, d2); sqlite3_bind_text(st, 8, d2, -1, SQLITE_TRANSIENT);
    int rc = sqlite3_step(st); sqlite3_finalize(st);
    return rc == SQLITE_DONE ? 0 : (rc == SQLITE_CONSTRAINT ? 14 : 17);
}
static int cust_delete(ckey_t *k) {
    sqlite3_stmt *st;
    sqlite3_prepare_v2(db, "DELETE FROM CUSTOMER WHERE CUSTOMER_SORTCODE=? AND CUSTOMER_NUMBER=?", -1, &st, 0);
    sqlite3_bind_text(st, 1, k->sc, -1, SQLITE_TRANSIENT); sqlite3_bind_text(st, 2, k->no, -1, SQLITE_TRANSIENT);
    sqlite3_step(st); sqlite3_finalize(st);
    return sqlite3_changes(db) ? 0 : 13;
}
/* browse: position by key including virtual control record (key 0000009999999999) */
static char br_key[17]; static int br_active;
static ckey_t upd_key; static int upd_held;   /* READ UPDATE TOKEN -> DELETE/REWRITE TOKEN */
static int cust_browse(int prev, unsigned char *r, char *keyout) {
    /* collect next/prev key relative to br_key */
    sqlite3_stmt *st; char best[17] = ""; int have = 0;
    const char *ctrl = "0000009999999999";
    sqlite3_prepare_v2(db, prev ? "SELECT CUSTOMER_SORTCODE||CUSTOMER_NUMBER k FROM CUSTOMER WHERE k<=? ORDER BY k DESC LIMIT 1"
                                : "SELECT CUSTOMER_SORTCODE||CUSTOMER_NUMBER k FROM CUSTOMER WHERE k>=? ORDER BY k ASC LIMIT 1", -1, &st, 0);
    sqlite3_bind_text(st, 1, br_key, -1, SQLITE_TRANSIENT);
    if (sqlite3_step(st) == SQLITE_ROW) { snprintf(best, sizeof best, "%s", sqlite3_column_text(st, 0)); have = 1; }
    sqlite3_finalize(st);
    if (prev ? strcmp(ctrl, br_key) <= 0 && (!have || strcmp(ctrl, best) > 0)
             : strcmp(ctrl, br_key) >= 0 && (!have || strcmp(ctrl, best) < 0)) { strcpy(best, ctrl); have = 1; }
    if (!have) return 20;
    ckey_t k; key_from((unsigned char *)best, &k); cust_read(&k, r);
    strcpy(keyout, best);
    /* step past current for the next call */
    char nk[17]; strcpy(nk, best);
    if (prev) { int i = 15; while (i >= 0 && nk[i] == '0') nk[i--] = '9'; if (i >= 0) nk[i]--; else return 0; }
    else { int i = 15; while (i >= 0 && nk[i] == '9') nk[i--] = '0'; if (i >= 0) nk[i]++; }
    strcpy(br_key, nk);
    return 0;
}

/* --------------------------------------------------------------- maps */
typedef struct { char name[24]; int loff, doff, len; } mfield_t;
static int load_map(const char *map, mfield_t *fs, int *nf, int *size) {
    char p[1024]; snprintf(p, sizeof p, "%s/../maps/%s.map", libdir, map);
    FILE *f = fopen(p, "r"); if (!f) return -1;
    char line[256]; *nf = 0;
    if (fgets(line, sizeof line, f)) { char a[32], b[32], c[32]; sscanf(line, "MAP %31s %31s %d", a, b, size); (void)c; }
    while (fgets(line, sizeof line, f)) {
        mfield_t *m = &fs[*nf]; if (sscanf(line, "%23s %d %d %d", m->name, &m->loff, &m->doff, &m->len) == 4) (*nf)++;
    }
    fclose(f); return 0;
}
static void emit_value(const char *tag, const char *name, const unsigned char *d, int n) {
    char buf[512]; int j = 0;
    for (int i = 0; i < n && j < 500; i++) {
        unsigned char c = d[i];
        if (c == '\\') { buf[j++] = '\\'; buf[j++] = '\\'; }
        else if (c < 32 || c > 126) j += snprintf(buf + j, 8, "\\x%02x", c);
        else buf[j++] = c;
    }
    while (j > 0 && buf[j - 1] == ' ') j--;
    buf[j] = 0;
    evf("%s\t%s\t%s", tag, name, buf);
}

/* ------------------------------------------------------- CICS entry */
static cont_t *find_cont(const char *chan, const char *name, int owner) {
    for (cont_t *c = conts; c; c = c->next) if (c->owner == owner && !strcmp(c->chan, chan) && !strcmp(c->name, name)) return c;
    return NULL;
}
static void put_cont(const char *chan, const char *name, int owner, const unsigned char *d, int len) {
    cont_t *c = find_cont(chan, name, owner);
    if (!c) { c = calloc(1, sizeof *c); snprintf(c->chan, 17, "%s", chan); snprintf(c->name, 17, "%s", name); c->owner = owner; c->next = conts; conts = c; }
    free(c->data); c->data = malloc(len > 0 ? len : 1); memcpy(c->data, d, len); c->len = len;
}
static char cur_channel[17] = "";

static void run_child(const char *transid, const char *chan, child_t *ch);

int CBSACICS(void *p1, ...) {
    cob_field *pf = cob_get_param_field(1, "CBSACICS");
    char prog[9]; field_get_str(pf, prog, sizeof prog);
    int id = (int)field_get_int(cob_get_param_field(2, "CBSACICS"));
    static cmd_t C0; cmd_t C = C0, *c = &C; c->m = get_meta(prog, id); c->fn = "CBSACICS"; cmd_capture(c);
    const char *v = c->m->verb;
    char s1[300], s2[300];
    (void)p1;

    if (!strcmp(v, "ASSIGN")) {
        cob_field *f;
        if ((f = opt(c, "APPLID"))) field_set_str(f, "CBSAHARN", -1);
        if ((f = opt(c, "SYSID"))) field_set_str(f, "CBSA", -1);
        if ((f = opt(c, "PROGRAM"))) field_set_str(f, cur_prog, -1);
        if ((f = opt(c, "USERID"))) field_set_str(f, "CBSAUSER", -1);
        if ((f = opt(c, "NETNAME"))) field_set_str(f, "CBSANET", -1);
        set_resp(c, 0, 0); return 0;
    }
    if (!strcmp(v, "ASKTIME")) {
        cob_field *f = opt(c, "ABSTIME");
        int64_t abst = (fixed_epoch + 2208988800LL + (int64_t)task_vtime()) * 1000LL + fixed_ms;
        if (f) field_set_int(f, abst);
        set_resp(c, 0, 0); return 0;
    }
    if (!strcmp(v, "FORMATTIME")) {
        int64_t abst = opt_int(c, "ABSTIME", 0);
        time_t t = (time_t)(abst / 1000 - 2208988800LL); struct tm tm; gmtime_r(&t, &tm);
        char sep = '/'; cob_field *f;
        if (has(c, "DATESEP")) { opt_str(c, "DATESEP", s1, sizeof s1); if (opt(c, "DATESEP")) { cob_field *df = opt(c, "DATESEP"); sep = df->data[0]; } }
        char dsep[2] = {0, 0}; if (has(c, "DATESEP")) dsep[0] = sep;
        if ((f = opt(c, "DDMMYYYY"))) { snprintf(s2, sizeof s2, "%02d%s%02d%s%04d", tm.tm_mday, dsep, tm.tm_mon + 1, dsep, tm.tm_year + 1900); field_set_str(f, s2, -1); }
        if ((f = opt(c, "YYYYMMDD"))) { snprintf(s2, sizeof s2, "%04d%s%02d%s%02d", tm.tm_year + 1900, dsep, tm.tm_mon + 1, dsep, tm.tm_mday); field_set_str(f, s2, -1); }
        if ((f = opt(c, "MMDDYYYY"))) { snprintf(s2, sizeof s2, "%02d%s%02d%s%04d", tm.tm_mon + 1, dsep, tm.tm_mday, dsep, tm.tm_year + 1900); field_set_str(f, s2, -1); }
        if ((f = opt(c, "TIME"))) {
            char ts[2] = {0, 0}; if (has(c, "TIMESEP")) { cob_field *tf = opt(c, "TIMESEP"); ts[0] = tf ? tf->data[0] : ':'; }
            snprintf(s2, sizeof s2, "%02d%s%02d%s%02d", tm.tm_hour, ts, tm.tm_min, ts, tm.tm_sec); field_set_str(f, s2, -1);
        }
        set_resp(c, 0, 0); return 0;
    }
    if (!strcmp(v, "LINK")) {
        opt_str(c, "PROGRAM", s1, sizeof s1);
        cob_field *ca = opt(c, "COMMAREA");
        int len = (int)opt_int(c, "LENGTH", ca ? (int64_t)ca->size : 0);
        if (!prog_exists(s1)) { set_resp(c, 27, 3); return 0; }
        call_prog(s1, ca ? ca->data : NULL, ca ? len : 0);
        set_resp(c, 0, 0); return 0;
    }
    if (!strcmp(v, "RETURN")) {
        if (has(c, "TRANSID")) {
            opt_str(c, "TRANSID", s1, sizeof s1);
            cob_field *ca = opt(c, "COMMAREA");
            int len = (int)opt_int(c, "LENGTH", ca ? (int64_t)ca->size : 0);
            evf("RETURN_TRANSID\t%s\t%s\t%d", s1, has(c, "IMMEDIATE") ? "IMMEDIATE" : "", ca ? len : 0);
            if (ca) emit_value("RETURN_COMMAREA", "-", ca->data, len);
        }
        set_resp(c, 0, 0); return 0;
    }
    if (!strcmp(v, "ABEND")) {
        opt_str(c, "ABCODE", s1, sizeof s1);
        do_abend(s1);
        return 0;
    }
    if (!strcmp(v, "HANDLE") || !strcmp(v, "ENQ") || !strcmp(v, "DEQ") || !strcmp(v, "SET")) {
        set_resp(c, 0, 0); return 0;
    }
    if (!strcmp(v, "SYNCPOINT")) {
        if (has(c, "ROLLBACK")) { sql_or_die("ROLLBACK"); evf("ROLLBACK\t%s", cur_prog); }
        else sql_or_die("COMMIT");
        sql_or_die("BEGIN");
        set_resp(c, 0, 0); return 0;
    }
    if (!strcmp(v, "DELAY")) {
        double secs = 0;
        if (has(c, "FOR") || has(c, "SECONDS") || has(c, "MILLISECS")) {
            secs = (double)opt_int(c, "SECONDS", 0) + opt_int(c, "MINUTES", 0) * 60 + opt_int(c, "HOURS", 0) * 3600 + opt_int(c, "MILLISECS", 0) / 1000.0;
        }
        if (cur_task) cur_task->vtime += secs;
        set_resp(c, 0, 0); return 0;
    }
    if (!strcmp(v, "INQUIRE")) {
        cob_field *f;
        if ((f = opt(c, "UCTRANST"))) field_set_int(f, 903);
        set_resp(c, 0, 0); return 0;
    }
    if (!strcmp(v, "BIF")) {
        cob_field *f = opt(c, "FIELD");
        if (f) {
            int n = (int)opt_int(c, "LENGTH", f->size); if (n > (int)f->size) n = (int)f->size;
            char d[300]; int j = 0;
            for (int i = 0; i < n; i++) if (isdigit(f->data[i])) d[j++] = f->data[i];
            memset(f->data, '0', n);
            for (int i = 0; i < j; i++) f->data[n - j + i] = d[i];
        }
        set_resp(c, 0, 0); return 0;
    }
    if (!strcmp(v, "READ") || !strcmp(v, "WRITE") || !strcmp(v, "REWRITE") || !strcmp(v, "DELETE") ||
        !strcmp(v, "STARTBR") || !strcmp(v, "READNEXT") || !strcmp(v, "READPREV") || !strcmp(v, "ENDBR")) {
        char file[16]; opt_str(c, "FILE", file, sizeof file);
        if (!strcmp(file, "ABNDFILE")) {
            cob_field *f = opt(c, "FROM");
            if (f) emit_value("ABNDFILE", cur_prog, f->data, (int)f->size);
            set_resp(c, 0, 0); return 0;
        }
        if (strcmp(file, "CUSTOMER")) { set_resp(c, 12, 1); return 0; }
        cob_field *rid = opt(c, "RIDFLD");
        unsigned char rec[CUSTLEN]; ckey_t k; int resp = 0;
        if (rid) key_from(rid->data, &k);
        if (!strcmp(v, "READ")) {
            if (!cust_read(&k, rec)) resp = 13;
            else { cob_field *into = opt(c, "INTO"); memcpy(into->data, rec, into->size < CUSTLEN ? into->size : CUSTLEN);
                   if (has(c, "UPDATE")) { upd_key = k; upd_held = 1; }
                   if (has(c, "TOKEN")) { cob_field *t = opt(c, "TOKEN"); if (t) field_set_int(t, 1); } }
        } else if (!strcmp(v, "WRITE")) {
            cob_field *from = opt(c, "FROM");
            if (cust_read(&k, rec) && !is_ctrl_key(&k)) resp = 14;
            else { unsigned char r2[CUSTLEN]; memset(r2, ' ', CUSTLEN); memcpy(r2, from->data, from->size < CUSTLEN ? from->size : CUSTLEN);
                   memcpy(r2 + 4, rid->data, 16); resp = cust_store(r2, 1); }
        } else if (!strcmp(v, "REWRITE")) {
            cob_field *from = opt(c, "FROM");
            unsigned char r2[CUSTLEN]; memset(r2, ' ', CUSTLEN); memcpy(r2, from->data, from->size < CUSTLEN ? from->size : CUSTLEN);
            resp = cust_store(r2, 0);
        } else if (!strcmp(v, "DELETE")) {
            if (rid) resp = cust_delete(&k);
            else if (upd_held) { resp = cust_delete(&upd_key); upd_held = 0; }
            else resp = 16;
        } else if (!strcmp(v, "STARTBR")) {
            memcpy(br_key, rid->data, 16); br_key[16] = 0; br_active = 1;
        } else if (!strcmp(v, "READNEXT") || !strcmp(v, "READPREV")) {
            char kk[17];
            resp = cust_browse(v[4] == 'P', rec, kk);
            if (!resp) { cob_field *into = opt(c, "INTO"); memcpy(into->data, rec, into->size < CUSTLEN ? into->size : CUSTLEN);
                         memcpy(rid->data, kk, 16); }
        } else br_active = 0;
        set_resp(c, resp, 0); return 0;
    }
    if (!strcmp(v, "PUT") || !strcmp(v, "GET")) {
        opt_str(c, "CONTAINER", s1, sizeof s1);
        char chan[17]; opt_str(c, "CHANNEL", chan, sizeof chan); if (!chan[0]) snprintf(chan, 17, "%s", cur_channel);
        if (v[0] == 'P') {
            cob_field *f = opt(c, "FROM"); int len = (int)opt_int(c, "FLENGTH", f->size);
            put_cont(chan, s1, chan_owner, f->data, len);
            set_resp(c, 0, 0);
        } else {
            cont_t *ct = find_cont(chan, s1, chan_owner);
            if (!ct) { set_resp(c, 110, 10); return 0; }
            cob_field *f = opt(c, "INTO"); int n = ct->len < (int)f->size ? ct->len : (int)f->size;
            memcpy(f->data, ct->data, n);
            cob_field *fl = opt(c, "FLENGTH"); if (fl) field_set_int(fl, ct->len);
            set_resp(c, 0, 0);
        }
        return 0;
    }
    if (!strcmp(v, "RUN")) {
        opt_str(c, "TRANSID", s1, sizeof s1);
        char chan[17]; opt_str(c, "CHANNEL", chan, sizeof chan);
        child_t *ch = &children[nchildren]; memset(ch, 0, sizeof *ch);
        ch->id = ++nchildren; ch->owner_id = chan_owner;
        snprintf(ch->token, 17, "CHILDTKN%08d", ch->id);
        snprintf(ch->chan, 17, "%s", chan); snprintf(ch->transid, 5, "%s", s1);
        evf("RUN_TRANSID\t%s\t%s\t%s", s1, chan, ch->token);
        run_child(s1, chan, ch);
        cob_field *t = opt(c, "CHILD"); if (t) field_set_str(t, ch->token, 16);
        set_resp(c, 0, 0); return 0;
    }
    if (!strcmp(v, "FETCH")) {
        double now = task_vtime(); child_t *best = NULL; int pending = 0;
        for (int i = 0; i < nchildren; i++) {
            child_t *ch = &children[i];
            if (ch->owner_id != chan_owner || ch->fetched) continue;
            if (ch->done_at <= now) { if (!best || ch->done_at < best->done_at) best = ch; }
            else pending++;
        }
        if (!best && pending && !has(c, "NOSUSPEND")) {
            for (int i = 0; i < nchildren; i++) { child_t *ch = &children[i];
                if (ch->owner_id == chan_owner && !ch->fetched && (!best || ch->done_at < best->done_at)) best = ch; }
            if (best && cur_task) cur_task->vtime = best->done_at;
        }
        if (!best) { set_resp(c, pending ? 125 : 13, 1); return 0; }
        best->fetched = 1;
        /* the child's returned channel becomes visible to the parent */
        for (cont_t *ct = conts; ct; ct = ct->next)
            if (ct->owner == 1000 + best->id) put_cont(best->chan, ct->name, chan_owner, ct->data, ct->len);
        cob_field *f;
        if ((f = opt(c, "ANY"))) field_set_str(f, best->token, 16);
        if ((f = opt(c, "CHANNEL"))) field_set_str(f, best->chan, -1);
        if ((f = opt(c, "COMPSTATUS"))) field_set_int(f, best->abcode[0] ? 1019 : 1018);
        if ((f = opt(c, "ABCODE"))) field_set_str(f, best->abcode, -1);
        evf("FETCH_CHILD\t%s\t%s", best->token, best->transid);
        set_resp(c, 0, 0); return 0;
    }
    if (!strcmp(v, "SEND") || !strcmp(v, "RECEIVE")) {
        char map[16], mapset[16]; opt_str(c, "MAP", map, sizeof map); opt_str(c, "MAPSET", mapset, sizeof mapset);
        if (!strcmp(v, "SEND") && has(c, "TEXT")) {
            cob_field *f = opt(c, "FROM"); int len = (int)opt_int(c, "LENGTH", f->size);
            emit_value("SEND_TEXT", has(c, "ERASE") ? "ERASE" : "-", f->data, len);
            set_resp(c, 0, 0); return 0;
        }
        if (!strcmp(v, "SEND") && has(c, "CONTROL")) { evf("SEND_CONTROL\t%s", has(c, "ERASE") ? "ERASE" : "-"); set_resp(c, 0, 0); return 0; }
        mfield_t fs[128]; int nf = 0, size = 0;
        if (load_map(map, fs, &nf, &size)) { set_resp(c, 38, 0); return 0; }
        if (!strcmp(v, "SEND")) {
            char o[200] = "";
            const char *names[] = {"ERASE", "DATAONLY", "MAPONLY", "CURSOR", "ALARM", "FREEKB", "ERASEAUP", NULL};
            for (int i = 0; names[i]; i++) if (has(c, names[i])) { strcat(o, names[i]); strcat(o, " "); }
            evf("SEND_MAP\t%s\t%s\t%s", map, mapset, o);
            cob_field *f = opt(c, "FROM");
            if (f && !has(c, "MAPONLY")) {
                for (int i = 0; i < nf; i++) {
                    unsigned char *d = f->data + fs[i].doff; int nz = 0;
                    for (int j = 0; j < fs[i].len; j++) if (d[j]) { nz = 1; break; }
                    if (nz) emit_value("FIELD", fs[i].name, d, fs[i].len);
                }
            }
            set_resp(c, 0, 0); return 0;
        }
        /* RECEIVE MAP: input from CBSA_MAPIN (lines NAME=value) */
        cob_field *f = opt(c, "INTO");
        memset(f->data, 0, f->size < (size_t)size ? f->size : (size_t)size);
        int got = 0; const char *mi = getenv("CBSA_MAPIN");
        FILE *in = mi ? fopen(mi, "r") : NULL;
        char line[600];
        while (in && fgets(line, sizeof line, in)) {
            line[strcspn(line, "\n")] = 0; char *eq = strchr(line, '='); if (!eq) continue; *eq = 0;
            for (int i = 0; i < nf; i++) if (!strcmp(fs[i].name, line)) {
                int l = (int)strlen(eq + 1); if (l > fs[i].len) l = fs[i].len;
                unsigned char *d = f->data + fs[i].doff;
                memset(d, ' ', fs[i].len); memcpy(d, eq + 1, l);
                put_be(f->data + fs[i].loff, 2, l); got++;
                evf("RECEIVE_FIELD\t%s\t%s", fs[i].name, eq + 1);
            }
        }
        if (in) fclose(in);
        evf("RECEIVE_MAP\t%s\t%s\t%d", map, mapset, got);
        set_resp(c, got ? 0 : 36, 0); return 0;
    }
    fprintf(stderr, "CBSACICS: unsupported verb %s in %s\n", v, prog);
    evf("UNSUPPORTED\t%s\t%s", prog, v);
    set_resp(c, 16, 0);
    return 0;
}

static void run_child(const char *transid, const char *chan, child_t *ch) {
    static const struct { const char *t, *p; } tx[] = {{"OCR1", "CRDTAGY1"}, {"OCR2", "CRDTAGY2"}, {"OCR3", "CRDTAGY3"},
                                                      {"OCR4", "CRDTAGY4"}, {"OCR5", "CRDTAGY5"}, {NULL, NULL}};
    const char *pg = NULL;
    for (int i = 0; tx[i].t; i++) if (!strcmp(tx[i].t, transid)) pg = tx[i].p;
    if (!pg) { snprintf(ch->abcode, 5, "AEY?"); ch->done_at = 0; return; }
    /* child gets a copy of the channel as it is now */
    for (cont_t *ct = conts; ct; ct = ct->next)
        if (ct->owner == chan_owner && !strcmp(ct->chan, chan)) put_cont(chan, ct->name, 1000 + ch->id, ct->data, ct->len);
    task_t *save = cur_task; int save_owner = chan_owner; char save_chan[17]; strcpy(save_chan, cur_channel);
    task_t t; eib_init(&t, transid, (save ? save->taskn : 1) + ch->id, 0, 0); t.vtime = 0;
    cur_task = &t; chan_owner = 1000 + ch->id; snprintf(cur_channel, 17, "%s", chan);
    sql_or_die("SAVEPOINT child");
    call_prog(pg, NULL, 0);
    sql_or_die("RELEASE child");
    ch->done_at = (save ? save->vtime : 0) + t.vtime;
    cur_task = save; chan_owner = save_owner; strcpy(cur_channel, save_chan);
    evf("CHILD_DONE\t%s\t%s\t%.3f", ch->token, pg, t.vtime);
}

/* --------------------------------------------------------------- SQL */
typedef struct cur { char prog[9]; char name[32]; sqlite3_stmt *st; struct cur *next; } cur_t;
static cur_t *cursors;
static int is_ddmmyyyy_dot(const char *s) {
    return strlen(s) == 10 && s[2] == '.' && s[5] == '.' && isdigit((unsigned char)s[0]) && isdigit((unsigned char)s[9]);
}
static void bind_field(sqlite3_stmt *st, int i, cob_field *f) {
    char b[400]; int sc;
    field_get_dec(f, b, sizeof b, &sc);
    if (sc < 0) {
        if (is_ddmmyyyy_dot(b)) { char iso[11]; snprintf(iso, 11, "%.4s-%.2s-%.2s", b + 6, b + 3, b); sqlite3_bind_text(st, i, iso, -1, SQLITE_TRANSIENT); }
        else sqlite3_bind_text(st, i, b, -1, SQLITE_TRANSIENT);
    } else if (sc == 0) sqlite3_bind_int64(st, i, atoll(b));
    else sqlite3_bind_text(st, i, b, -1, SQLITE_TRANSIENT);
}
static void out_field(sqlite3_stmt *st, int col, cob_field *f) {
    if (sqlite3_column_type(st, col) == SQLITE_NULL) return;
    if (field_is_num(f)) {
        if (sqlite3_column_type(st, col) == SQLITE_FLOAT) {
            char b[64]; int sc = f->attr->scale > 0 ? f->attr->scale : 0;
            snprintf(b, sizeof b, "%.*f", sc, sqlite3_column_double(st, col)); field_set_dec(f, b);
        } else field_set_dec(f, (const char *)sqlite3_column_text(st, col));
        return;
    }
    const char *t = (const char *)sqlite3_column_text(st, col);
    /* DATE columns come back in DB2 ISO format YYYY-MM-DD */
    field_set_str(f, t ? t : "", -1);
}
static void sqlca_set(cob_field *ca, int code, int rows, const char *msg) {
    unsigned char *p = ca->data;
    int32_t c32 = code; memcpy(p + 12, &c32, 4);
    int16_t ml = (int16_t)(msg ? strlen(msg) : 0); if (ml > 70) ml = 70; memcpy(p + 16, &ml, 2);
    memset(p + 18, ' ', 70); if (msg) memcpy(p + 18, msg, ml);
    for (int i = 0; i < 6; i++) { int32_t z = (i == 2) ? rows : 0; memcpy(p + 96 + 4 * i, &z, 4); }
    memcpy(p + 131, code == 0 ? "00000" : code == 100 ? "02000" : code == -803 ? "23505" : code == -811 ? "21000" : "58004", 5);
}
static char *db2_to_sqlite(const char *in) {
    char *s = strdup(in); char *p;
    while ((p = strcasestr(s, " FOR UPDATE"))) *p = 0;
    if ((p = strcasestr(s, " WITH UR"))) *p = 0;
    if ((p = strcasestr(s, " FETCH FIRST "))) {
        int n = 1; sscanf(p + 13, "%d", &n); char *r = malloc(strlen(s) + 32);
        *p = 0; sprintf(r, "%s LIMIT %d", s, n); free(s); s = r;
    }
    return s;
}
int CBSASQL(void *p1, ...) {
    (void)p1;
    char prog[9]; field_get_str(cob_get_param_field(1, "CBSASQL"), prog, sizeof prog);
    int id = (int)field_get_int(cob_get_param_field(2, "CBSASQL"));
    meta_t *m = get_meta(prog, id);
    cob_field *ca = cob_get_param_field(3, "CBSASQL");
    const char *k = m->sqlkind;
    if (!strcmp(k, "COMMIT")) { sql_or_die("COMMIT"); sql_or_die("BEGIN"); sqlca_set(ca, 0, 0, NULL); return 0; }
    if (!strcmp(k, "ROLLBACK")) { sql_or_die("ROLLBACK"); sql_or_die("BEGIN"); sqlca_set(ca, 0, 0, NULL); return 0; }
    if (!strcmp(k, "CLOSE")) {
        for (cur_t **pp = &cursors; *pp; pp = &(*pp)->next)
            if (!strcmp((*pp)->name, m->cursor) && !strcmp((*pp)->prog, prog)) { cur_t *x = *pp; sqlite3_finalize(x->st); *pp = x->next; free(x); break; }
        sqlca_set(ca, 0, 0, NULL); return 0;
    }
    if (!strcmp(k, "FETCH")) {
        cur_t *cu = NULL;
        for (cur_t *x = cursors; x; x = x->next) if (!strcmp(x->name, m->cursor) && !strcmp(x->prog, prog)) cu = x;
        if (!cu) { sqlca_set(ca, -501, 0, "cursor not open"); return 0; }
        int rc = sqlite3_step(cu->st);
        if (rc == SQLITE_ROW) { for (int i = 0; i < m->nout; i++) out_field(cu->st, i, cob_get_param_field(4 + i, "CBSASQL")); sqlca_set(ca, 0, 1, NULL); }
        else if (rc == SQLITE_DONE) sqlca_set(ca, 100, 0, NULL);
        else sqlca_set(ca, -999, 0, sqlite3_errmsg(db));
        return 0;
    }
    char *sql = db2_to_sqlite(m->sql);
    sqlite3_stmt *st;
    if (sqlite3_prepare_v2(db, sql, -1, &st, 0) != SQLITE_OK) {
        evf("SQLERROR\t%s\t%d\t%s\t%s", prog, id, sqlite3_errmsg(db), sql);
        sqlca_set(ca, -104, 0, sqlite3_errmsg(db)); free(sql); return 0;
    }
    free(sql);
    for (int i = 0; i < m->nin; i++) bind_field(st, i + 1, cob_get_param_field(4 + i, "CBSASQL"));
    if (!strcmp(k, "OPEN")) {
        cur_t *cu = calloc(1, sizeof *cu); strcpy(cu->prog, prog); snprintf(cu->name, 32, "%s", m->cursor); cu->st = st;
        cu->next = cursors; cursors = cu; sqlca_set(ca, 0, 0, NULL); return 0;
    }
    int rc = sqlite3_step(st);
    if (!strcmp(k, "SELECT")) {
        if (rc == SQLITE_ROW) {
            for (int i = 0; i < m->nout; i++) out_field(st, i, cob_get_param_field(4 + m->nin + i, "CBSASQL"));
            int more = sqlite3_step(st) == SQLITE_ROW;
            sqlca_set(ca, more ? -811 : 0, 1, NULL);
        } else if (rc == SQLITE_DONE) sqlca_set(ca, 100, 0, NULL);
        else sqlca_set(ca, -999, 0, sqlite3_errmsg(db));
    } else {
        if (rc == SQLITE_DONE) {
            int n = sqlite3_changes(db);
            int is_dml = !strncasecmp(m->sql, "UPDATE", 6) || !strncasecmp(m->sql, "DELETE", 6);
            sqlca_set(ca, (is_dml && n == 0) ? 100 : 0, n, NULL);
        } else if (sqlite3_extended_errcode(db) == SQLITE_CONSTRAINT_PRIMARYKEY || sqlite3_extended_errcode(db) == SQLITE_CONSTRAINT_UNIQUE)
            sqlca_set(ca, -803, 0, sqlite3_errmsg(db));
        else sqlca_set(ca, -999, 0, sqlite3_errmsg(db));
    }
    sqlite3_finalize(st);
    return 0;
}

/* -------------------------------------------------- LE callable services */
static int64_t lilian_of(int y, int m, int d) {
    /* days since 14 Oct 1582 (15 Oct 1582 = 1) */
    struct tm a = {0}, b = {0};
    if (y < 1582 || m < 1 || m > 12 || d < 1 || d > 31) return -1;
    a.tm_year = y - 1900; a.tm_mon = m - 1; a.tm_mday = d; a.tm_hour = 12;
    time_t ta = timegm(&a);
    struct tm chk; gmtime_r(&ta, &chk);
    if (chk.tm_mday != d || chk.tm_mon != m - 1) return -1;
    b.tm_year = 1582 - 1900; b.tm_mon = 9; b.tm_mday = 14; b.tm_hour = 12;
    /* proleptic Gregorian, which is what CEEDAYS uses after 1582-10-15 */
    return (int64_t)((ta - timegm(&b)) / 86400);
}
static void fc_set(cob_field *fc, int ok, int msg) {
    memset(fc->data, 0, fc->size);
    if (!ok) { put_be(fc->data, 2, 3); put_be(fc->data + 2, 2, msg); }
}
static int64_t now_lilian(void) {
    struct tm tm; now_tm(&tm, task_vtime()); return lilian_of(tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday);
}
int CEEDAYS(void *p1, void *p2, void *p3, void *p4) {
    (void)p1; (void)p2; (void)p3; (void)p4;
    cob_field *in = cob_get_param_field(1, "CEEDAYS"), *pic = cob_get_param_field(2, "CEEDAYS");
    cob_field *out = cob_get_param_field(3, "CEEDAYS"), *fc = cob_get_param_field(4, "CEEDAYS");
    int ilen = (int)get_be(in->data, 2), plen = (int)get_be(pic->data, 2);
    char s[64] = "", p[64] = "";
    if (ilen > (int)in->size - 2) ilen = (int)in->size - 2;
    if (plen > (int)pic->size - 2) plen = (int)pic->size - 2;
    memcpy(s, in->data + 2, ilen > 0 ? ilen : 0); memcpy(p, pic->data + 2, plen > 0 ? plen : 0);
    int y = 0, m = 0, d = 0;
    char *py = strstr(p, "YYYY"), *pm = strstr(p, "MM"), *pd = strstr(p, "DD");
    int ok = py && pm && pd;
    for (char *q = s; ok && *q && q < s + (py - p) + 4; q++) {}
    if (ok) {
        char t[8];
        snprintf(t, 5, "%.4s", s + (py - p)); y = atoi(t);
        snprintf(t, 3, "%.2s", s + (pm - p)); m = atoi(t);
        snprintf(t, 3, "%.2s", s + (pd - p)); d = atoi(t);
        for (int i = 0; i < 4; i++) if (!isdigit((unsigned char)s[(py - p) + i])) ok = 0;
    }
    int64_t l = ok ? lilian_of(y, m, d) : -1;
    if (l < 1) { field_set_int(out, 0); fc_set(fc, 0, 2518); return 0; }
    field_set_int(out, l); fc_set(fc, 1, 0); return 0;
}
int CEELOCT(void *p1, void *p2, void *p3, void *p4) {
    (void)p1; (void)p2; (void)p3; (void)p4;
    cob_field *l = cob_get_param_field(1, "CEELOCT"), *sec = cob_get_param_field(2, "CEELOCT");
    cob_field *g = cob_get_param_field(3, "CEELOCT"), *fc = cob_get_param_field(4, "CEELOCT");
    struct tm tm; now_tm(&tm, task_vtime());
    int64_t lil = now_lilian(); field_set_int(l, lil);
    double secs = (double)lil * 86400.0 + tm.tm_hour * 3600 + tm.tm_min * 60 + tm.tm_sec;
    if (sec->size == 8) memcpy(sec->data, &secs, 8);
    char b[32]; snprintf(b, sizeof b, "%04d%02d%02d%02d%02d%02d000", tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec);
    field_set_str(g, b, -1); fc_set(fc, 1, 0); return 0;
}
int CEEGMT(void *p1, void *p2, void *p3) {
    (void)p1; (void)p2; (void)p3;
    cob_field *l = cob_get_param_field(1, "CEEGMT"), *sec = cob_get_param_field(2, "CEEGMT"), *fc = cob_get_param_field(3, "CEEGMT");
    struct tm tm; now_tm(&tm, 0);
    int64_t lil = now_lilian(); field_set_int(l, lil);
    double secs = (double)lil * 86400.0 + tm.tm_hour * 3600 + tm.tm_min * 60 + tm.tm_sec;
    if (sec->size == 8) memcpy(sec->data, &secs, 8);
    fc_set(fc, 1, 0); return 0;
}
int CEEDATM(void *p1, void *p2, void *p3, void *p4) {
    (void)p1; (void)p2; (void)p3; (void)p4;
    cob_field *sec = cob_get_param_field(1, "CEEDATM"), *out = cob_get_param_field(3, "CEEDATM"), *fc = cob_get_param_field(4, "CEEDATM");
    double secs = 0; if (sec->size == 8) memcpy(&secs, sec->data, 8);
    int64_t days = (int64_t)(secs / 86400.0); int64_t rem = (int64_t)secs - days * 86400;
    struct tm b0 = {0}; b0.tm_year = 1582 - 1900; b0.tm_mon = 9; b0.tm_mday = 14;
    time_t t = timegm(&b0) + days * 86400 + rem; struct tm tm; gmtime_r(&t, &tm);
    char buf[32]; snprintf(buf, sizeof buf, "%04d%02d%02d%02d%02d%02d", tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec);
    field_set_str(out, buf, -1); fc_set(fc, 1, 0); return 0;
}

/* -------------------------------------------------------------- main */
static char aid_char(const char *n) {
    if (!n || !*n || !strcmp(n, "ENTER")) return '\'';
    if (!strcmp(n, "CLEAR")) return '_';
    if (!strcmp(n, "PA1")) return '%';
    if (!strcmp(n, "PA2")) return '>';
    if (!strcmp(n, "PA3")) return ',';
    if (!strncmp(n, "PF", 2)) { int k = atoi(n + 2); const char *t = "123456789:#@"; return (k >= 1 && k <= 12) ? t[k - 1] : '\''; }
    return '\'';
}
int main(int argc, char **argv) {
    if (argc < 7) { fprintf(stderr, "usage: cbsarun db link|term|batch PROG in out events\n"); return 2; }
    libdir = getenv("CBSA_LIB"); if (!libdir) libdir = "legacy/build/lib";
    setenv("COB_LIBRARY_PATH", libdir, 1);
    parse_fixed_time();
    { /* FUNCTION CURRENT-DATE (BANKDATA, CRECUST) follows the fixed clock too */
        time_t t = (time_t)fixed_epoch; struct tm tm; gmtime_r(&t, &tm); char cd[32];
        strftime(cd, sizeof cd, "%Y/%m/%d %H:%M:%S", &tm); setenv("COB_CURRENT_DATE", cd, 1); }
    const char *rs = getenv("CBSA_RANDOM_SEED"); rnd_base = rs ? atoll(rs) : 0; rnd_seed(rnd_base);
    if (sqlite3_open(argv[1], &db) != SQLITE_OK) { fprintf(stderr, "cannot open db\n"); return 2; }
    sqlite3_busy_timeout(db, 2000);
    ev = fopen(argv[6], "w");
    cob_init(0, NULL);
    sql_or_die("BEGIN");
    const char *mode = argv[2], *prog = argv[3];
    long taskn = getenv("CBSA_TASKN") ? atol(getenv("CBSA_TASKN")) : 100;
    static task_t top;
    if (!strcmp(mode, "batch")) {
        unsigned char parm[2 + 256]; memset(parm, ' ', sizeof parm);
        int l = (int)strlen(argv[4]); if (l > 256) l = 256;
        put_be(parm, 2, l); memcpy(parm + 2, argv[4], l);
        eib_init(&top, "BTCH", taskn, 0, 0); cur_task = &top;
        snprintf(cur_prog, 9, "%s", prog);
        void *av[1] = {parm};
        cob_call(prog, 1, av);
        sql_or_die("COMMIT");
        finish_and_exit(0);
    }
    unsigned char *ca = calloc(1, 32768); int calen = 0;
    FILE *f = fopen(argv[4], "rb");
    if (f) { calen = (int)fread(ca, 1, 32767, f); fclose(f); }
    top_commarea = ca; top_calen = calen; out_path = argv[5];
    const char *trn = getenv("CBSA_TRANSID"); if (!trn) trn = "CBSA";
    eib_init(&top, trn, taskn, calen, !strcmp(mode, "term") ? aid_char(getenv("CBSA_AID")) : '\'');
    cur_task = &top;
    snprintf(cur_prog, 9, "%s", prog);
    evf("START\t%s\t%s\t%d", mode, prog, calen);
    void *av[2] = {top.eib, ca};
    cob_call(prog, 2, av);
    sql_or_die("COMMIT");
    evf("END\t%s", prog);
    finish_and_exit(0);
    return 0;
}
