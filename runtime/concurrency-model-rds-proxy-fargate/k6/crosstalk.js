import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

// 「数の限られたスレッド（キャリアスレッド・イベントループ）をブロックすると、無関係なリクエストまで巻き込まれる」ことを k6 で測る。
// crosstalk.sh の AWS 版。
//
// crosstalk.sh（curl + bc）を残しているのは、k6 のない環境でも検証の核を確認できるようにするため。
// こちらが必要なのは AWS 上の計測で、負荷側の同時数を正確に保ち、観測側の遅延を
// パーセンタイルまで取れる必要があるから。curl の直列ループでは p99 が出せない。
//
// 使い方（環境変数）:
//   BASE_URL       計測対象
//   LOAD_MODE      負荷側のエンドポイント。webflux なら blocking、mvc-virtual なら pinned。
//                  対策の側（blocking-isolated / pinned-lock）もここに渡す。同じ台本で
//                  負荷側だけを差し替えるので、巻き込みが消えることを同じ尺度で比べられる
//   LOAD_VUS       負荷側の同時数
//   LOAD_HOLD_MS   負荷側の保持時間
//   PROBE_MODE     観測側。必ず DB を触らないモードにする
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const LOAD_MODE = __ENV.LOAD_MODE || 'blocking';
const LOAD_VUS = Number(__ENV.LOAD_VUS || 30);
const LOAD_HOLD_MS = __ENV.LOAD_HOLD_MS || '1000';
// 観測側が DB を触ると、遅延の原因がプール待ちなのかスレッド枯渇なのか切り分けられない
const PROBE_MODE = __ENV.PROBE_MODE || 'nodb';
const PROBE_HOLD_MS = __ENV.PROBE_HOLD_MS || '10';
const LABEL = __ENV.LABEL || `crosstalk-${LOAD_MODE}`;
const RESULTS_DIR = __ENV.RESULTS_DIR || 'k6/results';

// 負荷の前・中・後を別シナリオに分けている。1 本のシナリオを時間でタグ分けすると、
// 負荷の立ち上がり・立ち下がりの過渡状態が「負荷中」に混ざる
const PROBE_BEFORE = { start: 0, duration: 20 };
const LOAD = { start: 20, duration: 70 };
// 負荷が実際にスレッドを掴んでから、抜ける前までに収める
const PROBE_DURING = { start: 30, duration: 50 };
// 負荷側の保持時間ぶんの後始末を待ってから測る
const PROBE_AFTER = { start: LOAD.start + LOAD.duration + 5, duration: 20 };

const WINDOWS = ['before', 'during', 'after'];
const probeLatency = {};
for (const window of WINDOWS) {
    probeLatency[window] = new Trend(`probe_${window}`, true);
}
const loadLatency = new Trend('load_latency', true);
const loadOk = new Counter('load_ok');
const loadFailed = new Counter('load_failed');

export const options = {
    scenarios: {
        probe_before: probeScenario('probeBefore', PROBE_BEFORE),
        load: {
            executor: 'constant-vus',
            vus: LOAD_VUS,
            duration: `${LOAD.duration}s`,
            startTime: `${LOAD.start}s`,
            exec: 'load',
        },
        probe_during: probeScenario('probeDuring', PROBE_DURING),
        probe_after: probeScenario('probeAfter', PROBE_AFTER),
    },
    // 負荷側が失敗することも観測対象なので、閾値で中断させない
    thresholds: {},
    // 観測側は接続を張り直さない。毎回 TCP を張ると、測りたいサーバ側の遅延に
    // 接続確立のコストが混ざる
    noConnectionReuse: false,
    discardResponseBodies: true,
    // count を入れないと Trend の values に標本数が入らず、表の「標本数」が 0 で出る。
    // 観測側が実際に何回叩けたかは結論に直結する（巻き込まれて回数自体が減る）ので必ず出す
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};

function probeScenario(exec, window) {
    return {
        executor: 'constant-vus',
        vus: 1,
        duration: `${window.duration}s`,
        startTime: `${window.start}s`,
        exec,
    };
}

export function probeBefore() {
    probe('before');
}

export function probeDuring() {
    probe('during');
}

export function probeAfter() {
    probe('after');
}

function probe(window) {
    const res = http.get(`${BASE_URL}/api/${PROBE_MODE}?ms=${PROBE_HOLD_MS}`, {
        tags: { window },
        timeout: '60s',
    });
    probeLatency[window].add(res.timings.duration);
}

export function load() {
    const res = http.get(`${BASE_URL}/api/${LOAD_MODE}?ms=${LOAD_HOLD_MS}`, {
        tags: { window: 'load' },
        timeout: '60s',
    });
    loadLatency.add(res.timings.duration);
    if (res.status === 200) {
        loadOk.add(1);
    } else {
        loadFailed.add(1);
    }
}

export function handleSummary(data) {
    const rows = WINDOWS.map((window) => {
        const trend = data.metrics[`probe_${window}`];
        return {
            window,
            avg: round(trend?.values?.avg),
            p50: round(trend?.values?.['p(50)']),
            p99: round(trend?.values?.['p(99)']),
            max: round(trend?.values?.max),
            samples: trend?.values?.count ?? 0,
        };
    });

    const header = `| 負荷 | 観測 ${PROBE_MODE}(ms=${PROBE_HOLD_MS}) 平均 ms | p50 ms | p99 ms | max ms | 標本数 |\n|---|---|---|---|---|---|`;
    const names = { before: '負荷なし', during: '負荷中', after: '負荷終了後' };
    const body = rows
        .map((r) => `| ${names[r.window]} | ${r.avg} | ${r.p50} | ${r.p99} | ${r.max} | ${r.samples} |`)
        .join('\n');

    const loadSummary = `負荷: /api/${LOAD_MODE}?ms=${LOAD_HOLD_MS} x ${LOAD_VUS} VU`
        + ` 成功 ${data.metrics.load_ok?.values?.count ?? 0}`
        + ` / 失敗 ${data.metrics.load_failed?.values?.count ?? 0}`
        + ` / 平均 ${round(data.metrics.load_latency?.values?.avg)} ms`;

    const markdown = `### ${LABEL}\n\n${loadSummary}\n\n${header}\n${body}\n\n`
        + '負荷中だけ大きく伸びるなら、ブロックされているのは DB 接続ではなくスレッドそのもの。\n';

    return {
        stdout: `\n${markdown}\n`,
        [`${RESULTS_DIR}/${LABEL}.md`]: markdown,
        [`${RESULTS_DIR}/${LABEL}.json`]: JSON.stringify(
            { label: LABEL, loadMode: LOAD_MODE, loadVus: LOAD_VUS, probeMode: PROBE_MODE, rows }, null, 2),
    };
}

function round(value) {
    return value === undefined ? '-' : Math.round(value * 10) / 10;
}
