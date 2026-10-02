import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { buildScenarios, HOLD_SECONDS, LEVELS } from './lib/levels.js';

// 環境変数で条件を切り替える。アプリ側の設定（VIRTUAL_THREAD_ENABLED / POOL_SIZE）とは独立なので、
// 実行時のラベルとして LABEL を受け取り、結果ファイル名と出力に埋める。
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MODE = __ENV.MODE || 'db';
const HOLD_MS = __ENV.HOLD_MS || '200';
const LABEL = __ENV.LABEL || `${MODE}-hold${HOLD_MS}`;
// k6 の出力先は k6 プロセスの実行ディレクトリ基準。sweep.sh はプロジェクトルートから起動するため既定はここ
const RESULTS_DIR = __ENV.RESULTS_DIR || 'k6/results';
// 応答の backendConnectionId を標準出力に残す VU の数。RDS Proxy が Aurora 側の接続を
// 何本に束ねているかを厳密に数えるため、初出の ID だけを行として出す。
// 全 VU に出させるとログが数万行になり、CloudWatch から回収するときに肝心の集計表が
// 押し出されるので、先頭の数 VU だけを観測係にする（0 で無効）
const BACKEND_ID_OBSERVERS = Number(__ENV.BACKEND_ID_OBSERVERS || '10');

// k6のメトリクスは init 段階でしか作れないため、段ごとの系列をあらかじめ全部用意する。
const perLevel = {};
for (const level of LEVELS) {
    perLevel[level] = {
        latency: new Trend(`latency_L${level}`, true),
        ok: new Counter(`ok_L${level}`),
        failed: new Counter(`failed_L${level}`),
        rejected: new Counter(`rejected_L${level}`),
        // その VU がその段で見た backendConnectionId の種類数。値は 1, 2, 3... と増えるので
        // max がその段の「1 VU が見た接続の本数」になる。
        //
        // VU をまたいだ種類数（本当の distinct）はここでは出せない。k6 の VU は独立した
        // JS ランタイムで、集合を共有する手立てがなく、メトリクスは値の集約しかできないため。
        // VU をまたいだ数え上げは measure-aws.sh がログの BACKEND_ID 行から行う
        backendIds: new Trend(`backend_ids_L${level}`),
    };
}

// VU ごと（= JS ランタイムごと）の状態。段 -> 見た ID の集合。VU をまたいでは共有されない
const seenBackendIds = {};

export const options = {
    scenarios: buildScenarios('run'),
    // 高い同時接続数では失敗が出ることを期待している検証なので、閾値で中断させない。
    // 失敗率そのものが測定対象。
    thresholds: {},
    // 接続の再利用が計測に効くため明示する。毎回 TCP を張り直すと
    // 測りたい「DB 接続待ち」ではなく k6 側のコストを測ってしまう
    noConnectionReuse: false,
    discardResponseBodies: false,
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function run() {
    const level = levelFromScenario();
    const res = http.get(`${BASE_URL}/api/${MODE}?ms=${HOLD_MS}`, {
        tags: { level: String(level) },
        timeout: '60s',
    });

    const bucket = perLevel[level];
    if (!bucket) {
        return; // ウォームアップ段は集計しない
    }
    bucket.latency.add(res.timings.duration);
    if (res.status === 200) {
        bucket.ok.add(1);
        recordBackendId(level, bucket, res);
    } else if (res.status === 503) {
        // bounded モードが上限に達して意図的に断ったもの。障害とは区別する
        bucket.rejected.add(1);
    } else {
        bucket.failed.add(1);
    }
}

// その段で初めて見た backendConnectionId だけを記録する。
// nodb や blocking のような DB を触らないモードでは null が返るので何もしない
function recordBackendId(level, bucket, res) {
    const id = parseBackendId(res);
    if (id === null) {
        return;
    }
    let seen = seenBackendIds[level];
    if (!seen) {
        seen = new Set();
        seenBackendIds[level] = seen;
    }
    if (seen.has(id)) {
        return;
    }
    seen.add(id);
    bucket.backendIds.add(seen.size);
    if (isObserver(level)) {
        console.log(`BACKEND_ID level=${level} id=${id}`);
    }
}

// VU の ID はテスト全体の通し番号で、段ごとに 1 から振り直されるわけではない。
// 先頭 N VU だけを観測係にすると、その段で動いている VU が全部 N より後ろになることがあり、
// 実際に「同時 1」の段が 1 行も出ずに集計から丸ごと落ちた（ウォームアップが 1〜10 を使い、
// 同時 1 の段は 11 番目の VU になっていた）。
// 段の VU 数が観測係の数以下なら、その段は全 VU から出す。行数は VU 数に比例するので安全
function isObserver(level) {
    return level <= BACKEND_ID_OBSERVERS || exec.vu.idInTest <= BACKEND_ID_OBSERVERS;
}

function parseBackendId(res) {
    let body;
    try {
        body = res.json();
    } catch (e) {
        return null; // JSON でない応答（エラーページなど）。集計しない
    }
    const id = body?.backendConnectionId;
    return id === undefined || id === null ? null : id;
}

function levelFromScenario() {
    const name = exec.scenario.name;
    return name.startsWith('level_') ? Number(name.slice('level_'.length)) : 'warmup';
}

export function handleSummary(data) {
    const rows = LEVELS.map((level) => {
        const ok = count(data, `ok_L${level}`);
        const rejected = count(data, `rejected_L${level}`);
        const failed = count(data, `failed_L${level}`);
        const trend = data.metrics[`latency_L${level}`];
        // 標本がない段（DB を触らないモードなど）は Trend の max が 0 になる。
        // 「1 本見た」と読み違えないよう - にする
        const backendIdsPerVu = data.metrics[`backend_ids_L${level}`]?.values?.max || 0;
        return {
            level,
            rps: round(ok / HOLD_SECONDS),
            ok,
            rejected,
            failed,
            p50: round(trend?.values?.['p(50)']),
            p99: round(trend?.values?.['p(99)']),
            max: round(trend?.values?.max),
            backendIdsPerVuMax: backendIdsPerVu || '-',
        };
    });

    const header = `| 同時数 | 成功 rps | 成功数 | 503 | エラー | p50 ms | p99 ms | max ms | 接続 ID/VU |\n|---|---|---|---|---|---|---|---|---|`;
    const body = rows
        .map((r) => `| ${r.level} | ${r.rps} | ${r.ok} | ${r.rejected} | ${r.failed} | ${r.p50} | ${r.p99} | ${r.max} | ${r.backendIdsPerVuMax} |`)
        .join('\n');
    // 表だけを切り出して使うことがあるので、列の意味は表に付けて回す
    const note = '接続 ID/VU = その段で 1 つの VU が見た backendConnectionId の種類数（最大）。'
        + 'VU をまたいだ本数はログの BACKEND_ID 行から数える';
    const markdown = `### ${LABEL}\n\n${header}\n${body}\n\n${note}\n`;

    return {
        stdout: `\n${markdown}\n`,
        [`${RESULTS_DIR}/${LABEL}.md`]: markdown,
        [`${RESULTS_DIR}/${LABEL}.json`]: JSON.stringify({ label: LABEL, mode: MODE, holdMs: HOLD_MS, rows }, null, 2),
    };
}

function count(data, name) {
    return data.metrics[name]?.values?.count ?? 0;
}

function round(value) {
    return value === undefined ? '-' : Math.round(value * 10) / 10;
}
