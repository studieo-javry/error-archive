import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  clearConsent,
  isCollectionApproved,
  isSnoozedForSession,
  readConsent,
  setConsent,
} from '../src/consentIndex.js';

const NS = 'tok-ns-1';
const ROOT = '/dev/shop';

function tmpPath(): { path: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), 'ea-consent-'));
  return { path: join(dir, 'consent-index.json'), cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test('setConsent·readConsent 왕복(APPROVED)', () => {
  const { path, cleanup } = tmpPath();
  try {
    setConsent(NS, { projectKey: 'k', gitRoot: ROOT, status: 'APPROVED', remoteAtApproval: 'github.com/x' }, { path });
    const rec = readConsent(NS, ROOT, { path });
    assert.equal(rec?.status, 'APPROVED');
    assert.equal(rec?.projectKey, 'k');
    assert.equal(rec?.remoteAtApproval, 'github.com/x');
  } finally { cleanup(); }
});

test('isCollectionApproved — APPROVED + remote 일치면 true', () => {
  const { path, cleanup } = tmpPath();
  try {
    setConsent(NS, { projectKey: 'k', gitRoot: ROOT, status: 'APPROVED', remoteAtApproval: 'r1' }, { path });
    assert.equal(isCollectionApproved(NS, ROOT, 'r1', { path }), true);
  } finally { cleanup(); }
});

test('isCollectionApproved — remote 변경이면 false(재승인 필요)', () => {
  const { path, cleanup } = tmpPath();
  try {
    setConsent(NS, { projectKey: 'k', gitRoot: ROOT, status: 'APPROVED', remoteAtApproval: 'r1' }, { path });
    assert.equal(isCollectionApproved(NS, ROOT, 'r2', { path }), false);
  } finally { cleanup(); }
});

test('isCollectionApproved — SNOOZED·DECLINED·미존재는 false', () => {
  const { path, cleanup } = tmpPath();
  try {
    setConsent(NS, { projectKey: 'k', gitRoot: ROOT, status: 'SNOOZED', remoteAtApproval: 'r1', snoozeSessionId: 's1' }, { path });
    assert.equal(isCollectionApproved(NS, ROOT, 'r1', { path }), false);
    setConsent(NS, { projectKey: 'k', gitRoot: ROOT, status: 'DECLINED', remoteAtApproval: 'r1' }, { path });
    assert.equal(isCollectionApproved(NS, ROOT, 'r1', { path }), false);
    assert.equal(isCollectionApproved(NS, '/dev/none', 'r1', { path }), false);
  } finally { cleanup(); }
});

test('isCollectionApproved — 손상 파일은 false(fail-closed)', () => {
  const { path, cleanup } = tmpPath();
  try {
    writeFileSync(path, '{ not json');
    assert.equal(isCollectionApproved(NS, ROOT, 'r1', { path }), false);
  } finally { cleanup(); }
});

test('isSnoozedForSession — 같은 세션만 true', () => {
  const { path, cleanup } = tmpPath();
  try {
    setConsent(NS, { projectKey: 'k', gitRoot: ROOT, status: 'SNOOZED', remoteAtApproval: null, snoozeSessionId: 's1' }, { path });
    assert.equal(isSnoozedForSession(NS, ROOT, 's1', { path }), true);
    assert.equal(isSnoozedForSession(NS, ROOT, 's2', { path }), false);
    assert.equal(isSnoozedForSession(NS, ROOT, null, { path }), false);
  } finally { cleanup(); }
});

test('clearConsent — 레코드 제거 후 미승인(false)', () => {
  const { path, cleanup } = tmpPath();
  try {
    setConsent(NS, { projectKey: 'k', gitRoot: ROOT, status: 'APPROVED', remoteAtApproval: 'r1' }, { path });
    assert.equal(isCollectionApproved(NS, ROOT, 'r1', { path }), true);
    clearConsent(NS, ROOT, { path });
    assert.equal(isCollectionApproved(NS, ROOT, 'r1', { path }), false);
    assert.equal(readConsent(NS, ROOT, { path }), null);
  } finally { cleanup(); }
});

test('네임스페이스 격리 — 다른 토큰-ns 는 안 보임', () => {
  const { path, cleanup } = tmpPath();
  try {
    setConsent(NS, { projectKey: 'k', gitRoot: ROOT, status: 'APPROVED', remoteAtApproval: 'r1' }, { path });
    assert.equal(isCollectionApproved('other-ns', ROOT, 'r1', { path }), false);
  } finally { cleanup(); }
});
