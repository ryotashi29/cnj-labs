// 計測する同時接続数の段。ramping-vus で連続的に上げるのではなく、段ごとに独立した
// constant-vus シナリオを順番に流す構成にしている。理由は 2 つ。
//   1. 段ごとにタグが付くので「同時 100 のときの p99」を後から曖昧さなく取り出せる
//   2. 各段で定常状態に達してから測れる。連続ランプだと過渡状態が混ざる
const DEFAULT_LEVELS = [1, 5, 10, 25, 50, 100, 200, 400, 800];

// 段と測定時間は環境変数で絞れる。AWS 上では 1 モードあたり 5〜6 分かかるため、
// 条件（4 条件 x タスクサイズ 4 段）を広く取るときは段を減らさないと現実的な時間で終わらない。
// 既定は変えていないので、絞ったときだけ結果に断りが必要になる
export const LEVELS = (__ENV.LEVELS ? __ENV.LEVELS.split(',') : DEFAULT_LEVELS)
    .map((level) => Number(String(level).trim()))
    .filter((level) => Number.isFinite(level) && level > 0);

// 各段の測定時間と、段の間に挟む冷却時間。
export const HOLD_SECONDS = Number(__ENV.LEVEL_HOLD_SECONDS || 30);
export const HOLD = `${HOLD_SECONDS}s`;
export const COOLDOWN_SECONDS = 5;

// 最初の段の前に置くウォームアップ。JIT のコンパイルと HikariCP の初期接続確立を
// 計測から除くために必要。これがないと最初の段だけ不当に遅く出る。
export const WARMUP = '20s';
export const WARMUP_SECONDS = 20;

export function buildScenarios(execName) {
    const scenarios = {
        warmup: {
            executor: 'constant-vus',
            vus: 10,
            duration: WARMUP,
            exec: execName,
            startTime: '0s',
            tags: { level: 'warmup' },
        },
    };
    let startTime = WARMUP_SECONDS + COOLDOWN_SECONDS;
    for (const level of LEVELS) {
        scenarios[`level_${level}`] = {
            executor: 'constant-vus',
            vus: level,
            duration: HOLD,
            exec: execName,
            startTime: `${startTime}s`,
            tags: { level: String(level) },
        };
        startTime += HOLD_SECONDS + COOLDOWN_SECONDS;
    }
    return scenarios;
}
