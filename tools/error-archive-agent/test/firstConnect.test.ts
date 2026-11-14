import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { setConsent } from '../src/consentIndex.js';
import { buildFirstConnectNotice } from '../src/firstConnect.js';
import type { ResolvedProject } from '../src/project.js';

const NS = 'tok-ns';
const P: ResolvedProject = {
  gitRoot: '/dev/shop',
  remote: 'github.com/x/shop',
  projectKey: 'github.com/x/shop',
  trust: 'hook',
};

function tmp(): { path: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), 'ea-fc-'));
  return { path: join(dir, 'consent.json'), cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test('미승인(레코드 없음) → 안내 반환(projectKey 포함)', () => {
  const { path, cleanup } = tmp();
  try {
    const n = buildFirstConnectNotice(P, NS, 'sid-1', { path });
    assert.ok(n, '안내가 있어야 함');
    assert.ok(n!.systemMessage.includes(P.projectKey));
    assert.equal(n!.hookSpecificOutput.hookEventName, 'SessionStart');
    assert.ok(n!.hookSpecificOutput.additionalContext.includes(P.projectKey));
  } finally { cleanup(); }
});

test('APPROVED → 무안내(null)', () => {
  const { path, cleanup } = tmp();
  try {
    setConsent(NS, { projectKey: P.projectKey, gitRoot: P.gitRoot, status: 'APPROVED', remoteAtApproval: P.remote }, { path });
    assert.equal(buildFirstConnectNotice(P, NS, 'sid-1', { path }), null);
  } finally { cleanup(); }
});

test('SNOOZED — 같은 세션 무안내 / 다른 세션 안내', () => {
  const { path, cleanup } = tmp();
  try {
    setConsent(NS, { projectKey: P.projectKey, gitRoot: P.gitRoot, status: 'SNOOZED', remoteAtApproval: P.remote, snoozeSessionId: 'sid-1' }, { path });
    assert.equal(buildFirstConnectNotice(P, NS, 'sid-1', { path }), null); // 보류한 세션
    assert.ok(buildFirstConnectNotice(P, NS, 'sid-2', { path }), '다른 세션엔 다시 안내'); // /clear 후 새 세션 등
  } finally { cleanup(); }
});

test('DECLINED → 무안내(null)', () => {
  const { path, cleanup } = tmp();
  try {
    setConsent(NS, { projectKey: P.projectKey, gitRoot: P.gitRoot, status: 'DECLINED', remoteAtApproval: P.remote }, { path });
    assert.equal(buildFirstConnectNotice(P, NS, 'sid-1', { path }), null);
  } finally { cleanup(); }
});

test('APPROVED 이지만 remote 변경 → 다시 안내(재승인 필요)', () => {
  const { path, cleanup } = tmp();
  try {
    setConsent(NS, { projectKey: P.projectKey, gitRoot: P.gitRoot, status: 'APPROVED', remoteAtApproval: 'github.com/x/OLD' }, { path });
    assert.ok(buildFirstConnectNotice(P, NS, 'sid-1', { path }), 'remote 바뀌면 미승인 취급');
  } finally { cleanup(); }
});
