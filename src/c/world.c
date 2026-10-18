/*
 * world.c —— 世界状态机（C 端）
 *
 * 设计契约：
 *   - 只认 dim 和 delta，从不知道任何神的名字（解耦验证：grep 神名应为空）。
 *   - 持有 world_state；应用 delta；自然回归；tanh 软限幅；硬边界崩溃检测。
 *   - 不做 softmax、不采样随机、不计算任何行为逻辑。
 *   - 通过 stdin/stdout 的 JSON-lines 与 Java 通信；double 输出用 %.17g 保精度。
 *
 * 消息（每行一条 JSON）：
 *   Java -> C  init : {"type":"init","dims":[...],"relax":..,"soft_lo":..,
 *                       "soft_hi":..,"hard_lo":..,"hard_hi":..,"default_state":..}
 *   Java -> C  step : {"type":"step","deltas":[{"dim":"entropy","delta":0.05}, ...]}
 *   Java -> C  stop : {"type":"stop"}
 *   C    -> Java tick : {"type":"tick","t":N,"state":{"order":..},"collapse":null|"..."}
 *
 * 退出：收到 stop、stdin EOF、或被 SIGINT 打断（输出 {"type":"exit"}）。
 */

#define _GNU_SOURCE
#include <math.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "jsmn.h"

#define MAX_DIMS 64
#define NAME_LEN 64
#define LINE_LEN (1 << 16)

static volatile sig_atomic_t g_stop = 0;

static void on_sigint(int sig) {
    (void)sig;
    g_stop = 1;
}

/* ── 世界状态 ── */
static char   g_dims[MAX_DIMS][NAME_LEN];
static double g_state[MAX_DIMS];
static int    g_ndim = 0;
static double g_relax = 0.02;
static double g_soft_lo = -0.3, g_soft_hi = 1.3;
static double g_hard_lo = -0.1, g_hard_hi = 1.1;
static double g_def = 0.5;
static long   g_tick = 0;

static int dim_index(const char *name) {
    for (int i = 0; i < g_ndim; i++)
        if (strcmp(g_dims[i], name) == 0) return i;
    return -1;
}

static double soft_clip(double v) {
    double mid = (g_soft_lo + g_soft_hi) / 2.0;
    double half = (g_soft_hi - g_soft_lo) / 2.0;
    return mid + half * tanh((v - mid) / half * 1.5);
}

/* 计算从 token p 开始的对象/数组子树占用的 token 数（用于跳过）。
   注意 jsmn 的 size：对象 = 键值对数量，数组 = 元素数量（都不是子 token 数），
   因此必须递归展开嵌套。 */
static int span(const jsmntok_t *t, int ntok, int p) {
    if (p < 0 || p >= ntok) return 1;
    int type = t[p].type;
    if (type != JSMN_OBJECT && type != JSMN_ARRAY) return 1;
    int count = 1; /* 容器自身 */
    int i = p + 1;
    int elements = t[p].size;
    if (type == JSMN_ARRAY) {
        for (int e = 0; e < elements && i < ntok; e++) {
            int s = span(t, ntok, i);
            count += s; i += s;
        }
    } else { /* OBJECT：每个元素是 key + value 一对 */
        for (int e = 0; e < elements && i < ntok; e++) {
            int s1 = span(t, ntok, i); count += s1; i += s1; /* key */
            int s2 = span(t, ntok, i); count += s2; i += s2; /* value */
        }
    }
    return count;
}

/* 在 object（token obj）中找 key 对应的 value token 下标 */
static int find_key(const char *js, const jsmntok_t *t, int ntok, int obj,
                    const char *key) {
    if (obj < 0 || obj >= ntok || t[obj].type != JSMN_OBJECT) return -1;
    int size = t[obj].size;
    int i = obj + 1;
    for (int k = 0; k < size; k++) {
        if (i >= ntok || i + 1 >= ntok) return -1;
        if (t[i].type != JSMN_STRING) return -1;
        int len = t[i].end - t[i].start;
        int is_key = ((int)strlen(key) == len &&
                      strncmp(js + t[i].start, key, len) == 0);
        int val = i + 1;
        if (is_key) return val;
        i = val + span(t, ntok, val);
    }
    return -1;
}

static double tok_double(const char *js, const jsmntok_t *tok) {
    char buf[64];
    int len = tok->end - tok->start;
    if (len >= (int)sizeof(buf)) len = (int)sizeof(buf) - 1;
    memcpy(buf, js + tok->start, len);
    buf[len] = '\0';
    return strtod(buf, NULL);
}

static char *tok_str(const char *js, const jsmntok_t *tok, char *buf, int buflen) {
    int len = tok->end - tok->start;
    if (len >= buflen) len = buflen - 1;
    memcpy(buf, js + tok->start, len);
    buf[len] = '\0';
    return buf;
}

static void handle_init(const char *js, const jsmntok_t *t, int ntok, int root) {
    int v;
    if ((v = find_key(js, t, ntok, root, "relax")) >= 0) g_relax = tok_double(js, &t[v]);
    if ((v = find_key(js, t, ntok, root, "soft_lo")) >= 0) g_soft_lo = tok_double(js, &t[v]);
    if ((v = find_key(js, t, ntok, root, "soft_hi")) >= 0) g_soft_hi = tok_double(js, &t[v]);
    if ((v = find_key(js, t, ntok, root, "hard_lo")) >= 0) g_hard_lo = tok_double(js, &t[v]);
    if ((v = find_key(js, t, ntok, root, "hard_hi")) >= 0) g_hard_hi = tok_double(js, &t[v]);
    if ((v = find_key(js, t, ntok, root, "default_state")) >= 0) g_def = tok_double(js, &t[v]);

    int dims = find_key(js, t, ntok, root, "dims");
    if (dims >= 0 && t[dims].type == JSMN_ARRAY) {
        int n = t[dims].size;
        int p = dims + 1;
        g_ndim = 0;
        for (int k = 0; k < n && g_ndim < MAX_DIMS; k++) {
            if (p >= ntok) break;
            if (t[p].type == JSMN_STRING) {
                char buf[NAME_LEN];
                tok_str(js, &t[p], buf, sizeof(buf));
                strcpy(g_dims[g_ndim], buf);   /* buf 已由 tok_str 截断并补零 */
                g_state[g_ndim] = g_def;
                g_ndim++;
            }
            p++; /* 字符串数组，每元素 1 token */
        }
    }
}

static void handle_step(const char *js, const jsmntok_t *t, int ntok, int root) {
    static double acc[MAX_DIMS];
    for (int i = 0; i < g_ndim; i++) acc[i] = 0.0;

    int deltas = find_key(js, t, ntok, root, "deltas");
    if (deltas >= 0 && t[deltas].type == JSMN_ARRAY) {
        int n = t[deltas].size;
        int p = deltas + 1;
        for (int k = 0; k < n; k++) {
            if (p >= ntok || t[p].type != JSMN_OBJECT) { p++; continue; }
            int dim_tok = find_key(js, t, ntok, p, "dim");
            int val_tok = find_key(js, t, ntok, p, "delta");
            if (dim_tok >= 0 && val_tok >= 0) {
                char buf[NAME_LEN];
                tok_str(js, &t[dim_tok], buf, sizeof(buf));
                int idx = dim_index(buf);
                if (idx >= 0) acc[idx] += tok_double(js, &t[val_tok]);
            }
            p += span(t, ntok, p);
        }
    }

    /* 应用 + 自然回归 + 软限幅 */
    for (int i = 0; i < g_ndim; i++) {
        g_state[i] += acc[i];
        g_state[i] += (g_def - g_state[i]) * g_relax;
        g_state[i] = soft_clip(g_state[i]);
    }

    /* 硬边界崩溃检测 */
    const char *collapse = NULL;
    char colbuf[160];
    for (int i = 0; i < g_ndim; i++) {
        if (g_state[i] < g_hard_lo || g_state[i] > g_hard_hi) {
            snprintf(colbuf, sizeof(colbuf),
                     "维度[%s]越界 (%+.17g 超出 [%+.17g,%+.17g])",
                     g_dims[i], g_state[i], g_hard_lo, g_hard_hi);
            collapse = colbuf;
            break;
        }
    }

    /* 输出 tick（精度 %.17g） */
    fputs("{\"type\":\"tick\",\"t\":", stdout);
    fprintf(stdout, "%ld", g_tick);
    fputs(",\"state\":{", stdout);
    for (int i = 0; i < g_ndim; i++) {
        if (i) fputc(',', stdout);
        fputs("\"", stdout);
        fputs(g_dims[i], stdout);
        fputs("\":", stdout);
        fprintf(stdout, "%.17g", g_state[i]);
    }
    fputs("},\"collapse\":", stdout);
    if (collapse) {
        fputs("\"", stdout);
        fputs(collapse, stdout);
        fputs("\"", stdout);
    } else {
        fputs("null", stdout);
    }
    fputs("}\n", stdout);
    fflush(stdout);

    g_tick++;
}

int main(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_sigint;
    sigaction(SIGINT, &sa, NULL);

    char *line = NULL;
    size_t cap = 0;
    jsmn_parser parser;

    while (!g_stop) {
        ssize_t r = getline(&line, &cap, stdin);
        if (r < 0) break;                       /* EOF */
        while (r > 0 && (line[r - 1] == '\n' || line[r - 1] == '\r'))
            line[--r] = '\0';
        if (r == 0) continue;

        jsmn_init(&parser);
        jsmntok_t toks[2048];
        int ntok = jsmn_parse(&parser, line, r, toks, 2048);
        if (ntok < 1 || toks[0].type != JSMN_OBJECT) continue;

        int type_tok = find_key(line, toks, ntok, 0, "type");
        char typ[32] = {0};
        if (type_tok >= 0) tok_str(line, &toks[type_tok], typ, sizeof(typ));

        if (strcmp(typ, "init") == 0) {
            handle_init(line, toks, ntok, 0);
        } else if (strcmp(typ, "step") == 0) {
            handle_step(line, toks, ntok, 0);
        } else if (strcmp(typ, "stop") == 0) {
            break;
        }
    }

    free(line);
    fprintf(stdout, "{\"type\":\"exit\",\"t\":%ld}\n", g_tick);
    fflush(stdout);
    return 0;
}
