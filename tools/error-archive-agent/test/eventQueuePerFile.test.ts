import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { EventQueue } from '../src/eventQueue.js';

function tmp() {
  const dir = mkdtempSync(join(tmpdir(), 'ea-pf-'));
  return { dir, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}
const migratingCount = (dir: string) => readdirSync(dir).filter((f) => f.startsWith('ns.jsonl.migrating.')).length;
const ev = (id: string, observedAt = '2026-11-10T00:00:00Z') =>
  ({ clientEventId: id, eventType: 'PostToolUse:Bash', localSequence: 1, observedAt, redactedPayload: {}, truncated: false, evidenceRefs: [] }) as never;
const ids = (q: EventQueue) => q.readAll().map((e) => (e as { clientEventId: string }).clientEventId).sort();
const q = (dir: string, opts = {}) => new EventQueue('ns', { dir, ...opts });

// 1. 다중 프로세스 동시 적재 — 유실 없음(각 이벤트가 독립 파일)
test('다중 프로세스 동시 적재 — 모두 보존(독립 파일)', () => {
  const { dir, cleanup } = tmp();
  try {
    const p1 = q(dir); // 프로세스 1
    const p2 = q(dir); // 프로세스 2(같은 큐 디렉토리)
    p1.enqueue(ev('aa1'));
    p2.enqueue(ev('bb2'));
    p1.enqueue(ev('cc3'));
    assert.deepEqual(ids(q(dir)), ['aa1', 'bb2', 'cc3']);
  } finally { cleanup(); }
});

// 2. Sender 전송·삭제 중 새 이벤트 적재 — 새 이벤트 유실 없음
test('remove 와 enqueue 동시(다른 파일) — 새 이벤트 유실 없음', () => {
  const { dir, cleanup } = tmp();
  try {
    const s = q(dir);
    s.enqueue(ev('aa1'));
    s.enqueue(ev('bb2'));
    // Sender 가 aa1 삭제하는 사이 Hook 이 cc3 적재 — 다른 파일이라 무간섭
    s.remove(new Set(['aa1']));
    s.enqueue(ev('cc3'));
    assert.deepEqual(ids(s), ['bb2', 'cc3'], '삭제한 것만 빠지고 새것은 보존');
  } finally { cleanup(); }
});

// 3. 파일 작성 중 강제 종료 — 미완성 .tmp 는 무시(복구)
test('파일 작성 중 종료(.tmp 잔존) — readAll 이 무시', () => {
  const { dir, cleanup } = tmp();
  try {
    const s = q(dir);
    s.enqueue(ev('aa1')); // 정상 완료본
    // 크래시로 남은 미완성 tmp(같은 샤드 디렉토리)
    const shardDir = join(dir, 'ns', 'pending', 'aa');
    mkdirSync(shardDir, { recursive: true });
    writeFileSync(join(shardDir, 'aa9.json.12345.tmp'), '{"partial"'); // 깨진 미완성
    assert.deepEqual(ids(s), ['aa1'], '.tmp 는 이벤트로 보이지 않음');
  } finally { cleanup(); }
});

// 4. 서버 전송 성공 후 삭제 전 비정상 종료 — 파일 잔존 → 다음 실행이 재전송 가능
test('전송 성공 후 remove 전 종료 — 이벤트 파일 잔존(재전송 대상)', () => {
  const { dir, cleanup } = tmp();
  try {
    q(dir).enqueue(ev('aa1'));
    // 전송 성공했지만 remove 호출 전에 프로세스가 죽었다고 가정 → 재기동(새 인스턴스)
    const restarted = q(dir);
    assert.deepEqual(ids(restarted), ['aa1'], '아직 안 지워졌으므로 재전송 대상으로 남음');
    // 재전송 후 서버가 DUPLICATE → 이제 remove
    restarted.remove(new Set(['aa1']));
    assert.deepEqual(ids(restarted), []);
  } finally { cleanup(); }
});

// 5. 기존 JSONL 마이그레이션 + 중복 방지(멱등)
test('레거시 JSONL 이관 + 재이관 중복 없음(멱등)', () => {
  const { dir, cleanup } = tmp();
  try {
    const legacy = join(dir, 'ns.jsonl');
    writeFileSync(legacy, [JSON.stringify(ev('aa1')), JSON.stringify(ev('bb2'))].join('\n') + '\n');
    const s = q(dir);
    s.migrateLegacy();
    assert.deepEqual(ids(s), ['aa1', 'bb2'], '이벤트별 파일로 이관');
    assert.equal(existsSync(legacy), false, '전량 이관 후 레거시 삭제');
    // 구버전 Hook 이 레거시에 다시 기록(bb2 중복 + cc3 신규)
    writeFileSync(legacy, [JSON.stringify(ev('bb2')), JSON.stringify(ev('cc3'))].join('\n') + '\n');
    s.migrateLegacy();
    assert.deepEqual(ids(s), ['aa1', 'bb2', 'cc3'], 'bb2 중복 없이 cc3 만 추가');
  } finally { cleanup(); }
});

// 6. 네트워크 장애 후 전체 복구 — remove 안 하면 전량 보존, 재기동도 보존
test('네트워크 장애(전송 실패, remove 안 됨) — 전체 보존 + 재기동 복구', () => {
  const { dir, cleanup } = tmp();
  try {
    const s = q(dir);
    for (const i of [1, 2, 3, 4, 5]) s.enqueue(ev(`e${i}`, `2026-11-10T00:00:0${i}Z`));
    assert.equal(s.size(), 5);
    // 재기동(새 인스턴스) — durable
    assert.deepEqual(ids(q(dir)), ['e1', 'e2', 'e3', 'e4', 'e5']);
  } finally { cleanup(); }
});

// 보조: 샤딩(앞 2자리) / rejected 이동 / 디스크 백프레셔 / 순서
test('샤딩 — clientEventId 앞 2자리 디렉토리에 저장', () => {
  const { dir, cleanup } = tmp();
  try {
    q(dir).enqueue(ev('ab-x'));
    assert.ok(existsSync(join(dir, 'ns', 'pending', 'ab', 'ab-x.json')));
  } finally { cleanup(); }
});

test('rejected 는 rejected/ 로 이동(dead-letter)', () => {
  const { dir, cleanup } = tmp();
  try {
    const s = q(dir);
    s.enqueue(ev('aa1'));
    s.remove(new Set(), [ev('aa1')]); // rejected 처리
    assert.deepEqual(ids(s), [], 'pending 에서 제거');
    assert.ok(existsSync(join(dir, 'ns', 'rejected', 'aa', 'aa1.json')), 'rejected 로 이동');
  } finally { cleanup(); }
});

test('디스크 여유 부족 백프레셔 — enqueue 건너뜀', () => {
  const { dir, cleanup } = tmp();
  try {
    const full = q(dir, { minFreeBytes: Number.MAX_SAFE_INTEGER }); // 항상 '여유 부족'
    full.enqueue(ev('aa1'));
    assert.equal(full.size(), 0, '백프레셔로 적재 안 됨');
    const ok = q(dir, { minFreeBytes: 0 });
    ok.enqueue(ev('bb2'));
    assert.equal(ok.size(), 1);
  } finally { cleanup(); }
});

test('순서 — observedAt 오름차순, 동률이면 clientEventId', () => {
  const { dir, cleanup } = tmp();
  try {
    const s = q(dir);
    s.enqueue(ev('z', '2026-11-10T00:00:03Z'));
    s.enqueue(ev('a', '2026-11-10T00:00:01Z'));
    s.enqueue(ev('m', '2026-11-10T00:00:01Z'));
    assert.deepEqual(s.readAll().map((e) => (e as { clientEventId: string }).clientEventId), ['a', 'm', 'z']);
  } finally { cleanup(); }
});

// #1(피드백 P0): 마이그레이션 중 디스크 백프레셔로 미저장이면 레거시(조각)를 지우지 않음 → 유실 방지
test('마이그레이션: 디스크 백프레셔로 미저장이면 조각 유지(유실 방지), 회복 후 재처리', () => {
  const { dir, cleanup } = tmp();
  try {
    writeFileSync(join(dir, 'ns.jsonl'), JSON.stringify(ev('aa1')) + '\n');
    q(dir, { minFreeBytes: Number.MAX_SAFE_INTEGER }).migrateLegacy(); // enqueue=false → 미저장
    assert.equal(q(dir).size(), 0, '백프레셔로 미저장');
    assert.equal(migratingCount(dir), 1, '미완료 조각이 남아 다음에 재시도(레거시 삭제 안 됨)');
    // 디스크 회복 → 조각 재처리로 복구
    q(dir, { minFreeBytes: 0 }).migrateLegacy();
    assert.deepEqual(ids(q(dir)), ['aa1']);
    assert.equal(migratingCount(dir), 0, '조각 정리됨');
  } finally { cleanup(); }
});

// #3(피드백 P0): claim 이후 레거시에 추가된 이벤트는 다음 호출이 처리 → 구버전 Hook append 유실 방지
test('마이그레이션: claim 후 새 레거시 append 는 다음 호출이 처리(무유실)', () => {
  const { dir, cleanup } = tmp();
  try {
    const legacy = join(dir, 'ns.jsonl');
    const s = q(dir);
    writeFileSync(legacy, JSON.stringify(ev('aa1')) + '\n');
    s.migrateLegacy();
    assert.deepEqual(ids(s), ['aa1']);
    // 구버전 Hook 이 그 사이 새 레거시에 bb2 기록
    writeFileSync(legacy, JSON.stringify(ev('bb2')) + '\n');
    s.migrateLegacy();
    assert.deepEqual(ids(s), ['aa1', 'bb2'], 'append 분도 유실 없이 이관');
    assert.equal(existsSync(legacy), false);
  } finally { cleanup(); }
});

// #3 보조: 크래시로 남은 미완료 .migrating 조각은 다음 호출에서 복구
test('마이그레이션: 미완료 .migrating 조각은 다음 호출에서 재처리(복구)', () => {
  const { dir, cleanup } = tmp();
  try {
    writeFileSync(join(dir, 'ns.jsonl.migrating.leftover'), JSON.stringify(ev('cc3')) + '\n');
    const s = q(dir);
    s.migrateLegacy(); // step1 에서 조각 드레인
    assert.deepEqual(ids(s), ['cc3']);
    assert.equal(migratingCount(dir), 0, '조각 정리됨');
  } finally { cleanup(); }
});

// #4(피드백 P1): 실제 다중 프로세스 병렬 적재 — 순차가 아니라 별도 프로세스 동시 실행, 무손실
test('실제 다중 프로세스 병렬 적재 — 전부 보존', async () => {
  const { dir, cleanup } = tmp();
  try {
    const PROCS = 4;
    const PER = 25;
    const tsxBin = join(process.cwd(), 'node_modules', '.bin', 'tsx');
    const worker = join(process.cwd(), 'test', 'parallelEnqueueWorker.ts');
    await Promise.all(
      Array.from({ length: PROCS }, (_, k) =>
        new Promise<void>((resolve, reject) => {
          const cp = spawn(tsxBin, [worker, dir, `p${k}`, String(PER)], { stdio: 'ignore' });
          cp.on('exit', (code) => (code === 0 ? resolve() : reject(new Error(`worker ${k} exit ${code}`))));
          cp.on('error', reject);
        }),
      ),
    );
    assert.equal(q(dir).size(), PROCS * PER, '모든 프로세스의 이벤트가 유실 없이 저장됨');
  } finally { cleanup(); }
});

// PR-5: 세션별 미전송 수(봉인 완결성 판단용)
test('pendingForSession — 해당 세션 이벤트만 카운트', () => {
  const { dir, cleanup } = tmp();
  try {
    const evS = (id: string, sid: string) =>
      ({ clientEventId: id, eventType: 'PostToolUse:Bash', localSequence: 1, observedAt: '2026-11-10T00:00:00Z', redactedPayload: {}, truncated: false, evidenceRefs: [], sessionId: sid }) as never;
    const s = q(dir);
    s.enqueue(evS('a1', 's1'));
    s.enqueue(evS('a2', 's1'));
    s.enqueue(evS('b1', 's2'));
    assert.equal(s.pendingForSession('s1'), 2);
    assert.equal(s.pendingForSession('s2'), 1);
    assert.equal(s.pendingForSession('s3'), 0);
  } finally { cleanup(); }
});
