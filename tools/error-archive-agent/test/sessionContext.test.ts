import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { readContext, recordContext } from '../src/sessionContext.js';

const ns = 'acct-ns-1';

function tempCtx(): { path: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), 'ea-sessctx-'));
  return { path: join(dir, 'session-context.json'), cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test('기록 후 session_id 로 조회(preferSessionId 우선)', () => {
  const { path, cleanup } = tempCtx();
  try {
    recordContext(ns, { sessionId: 's1', cwd: '/dev/a' }, { path });
    recordContext(ns, { sessionId: 's2', cwd: '/dev/b' }, { path });
    const got = readContext(ns, { path, preferSessionId: 's1' });
    assert.equal(got?.cwd, '/dev/a');
    assert.equal(got?.ambiguous, false);
  } finally {
    cleanup();
  }
});

test('session_id 별 격리 — 다중 세션이 서로 덮어쓰지 않음', () => {
  const { path, cleanup } = tempCtx();
  try {
    recordContext(ns, { sessionId: 's1', cwd: '/dev/a' }, { path });
    recordContext(ns, { sessionId: 's2', cwd: '/dev/b' }, { path });
    assert.equal(readContext(ns, { path, preferSessionId: 's1' })?.cwd, '/dev/a');
    assert.equal(readContext(ns, { path, preferSessionId: 's2' })?.cwd, '/dev/b');
  } finally {
    cleanup();
  }
});

test('preferSessionId 없으면 최신 엔트리 + 복수면 ambiguous', () => {
  const { path, cleanup } = tempCtx();
  try {
    recordContext(ns, { sessionId: 's1', cwd: '/dev/a' }, { path });
    // s2 를 더 최근에 기록.
    recordContext(ns, { sessionId: 's2', cwd: '/dev/b' }, { path });
    const got = readContext(ns, { path });
    assert.equal(got?.cwd, '/dev/b'); // 최신
    assert.equal(got?.ambiguous, true); // 복수 세션 → 모호
  } finally {
    cleanup();
  }
});

test('단일 세션이면 ambiguous=false', () => {
  const { path, cleanup } = tempCtx();
  try {
    recordContext(ns, { sessionId: 's1', cwd: '/dev/a' }, { path });
    const got = readContext(ns, { path });
    assert.equal(got?.cwd, '/dev/a');
    assert.equal(got?.ambiguous, false);
  } finally {
    cleanup();
  }
});

test('같은 세션 재기록은 cwd 갱신(프로젝트 전환)', () => {
  const { path, cleanup } = tempCtx();
  try {
    recordContext(ns, { sessionId: 's1', cwd: '/dev/a' }, { path });
    recordContext(ns, { sessionId: 's1', cwd: '/dev/switched' }, { path });
    assert.equal(readContext(ns, { path, preferSessionId: 's1' })?.cwd, '/dev/switched');
  } finally {
    cleanup();
  }
});

test('cwd 없으면 기록하지 않음', () => {
  const { path, cleanup } = tempCtx();
  try {
    recordContext(ns, { sessionId: 's1' }, { path });
    recordContext(ns, { sessionId: 's1', cwd: '  ' }, { path });
    assert.equal(readContext(ns, { path }), null);
  } finally {
    cleanup();
  }
});

test('네임스페이스 격리 — 다른 계정 문맥은 안 보임', () => {
  const { path, cleanup } = tempCtx();
  try {
    recordContext(ns, { sessionId: 's1', cwd: '/dev/a' }, { path });
    assert.equal(readContext('other-acct', { path }), null);
  } finally {
    cleanup();
  }
});

test('24h 초과 엔트리는 prune 된다', () => {
  const { path, cleanup } = tempCtx();
  try {
    const old = new Date(Date.now() - 48 * 60 * 60 * 1000).toISOString();
    writeFileSync(path, `${JSON.stringify({ [ns]: { sOld: { sessionId: 'sOld', cwd: '/dev/old', updatedAt: old } } })}\n`);
    // 새 기록이 prune 을 트리거.
    recordContext(ns, { sessionId: 's1', cwd: '/dev/a' }, { path });
    assert.equal(readContext(ns, { path, preferSessionId: 'sOld' }), null);
    assert.equal(readContext(ns, { path, preferSessionId: 's1' })?.cwd, '/dev/a');
  } finally {
    cleanup();
  }
});
