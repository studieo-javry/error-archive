import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { approve, getApproved, namespaceOf, revoke } from '../src/projectState.js';

function tempState(): { path: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), 'ea-state-'));
  return { path: join(dir, 'projects.json'), cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

const ns = namespaceOf('https://api.ea.test', 7);
const root = '/Users/me/dev/checkout';

test('approve 후 getApproved 로 projectKey 조회(같은 remote)', () => {
  const { path, cleanup } = tempState();
  try {
    approve(ns, root, { projectKey: 'github.com/org/checkout', remote: 'github.com/org/checkout' }, { path });
    const got = getApproved(ns, root, 'github.com/org/checkout', { path });
    assert.equal(got?.projectKey, 'github.com/org/checkout');
  } finally {
    cleanup();
  }
});

test('remote 가 바뀌면(저장소 교체) 미승인으로 간주', () => {
  const { path, cleanup } = tempState();
  try {
    approve(ns, root, { projectKey: 'github.com/org/checkout', remote: 'github.com/org/checkout' }, { path });
    assert.equal(getApproved(ns, root, 'github.com/org/OTHER', { path }), null);
  } finally {
    cleanup();
  }
});

test('계정 네임스페이스 격리 — 다른 서버/사용자 승인은 재사용 안 됨', () => {
  const { path, cleanup } = tempState();
  try {
    approve(ns, root, { projectKey: 'k', remote: null }, { path });
    const otherUser = namespaceOf('https://api.ea.test', 99);
    const otherServer = namespaceOf('https://other.ea.test', 7);
    assert.equal(getApproved(otherUser, root, null, { path }), null);
    assert.equal(getApproved(otherServer, root, null, { path }), null);
    assert.equal(getApproved(ns, root, null, { path })?.projectKey, 'k'); // 본 계정은 유지
  } finally {
    cleanup();
  }
});

test('revoke 후 미승인', () => {
  const { path, cleanup } = tempState();
  try {
    approve(ns, root, { projectKey: 'k', remote: null }, { path });
    revoke(ns, root, { path });
    assert.equal(getApproved(ns, root, null, { path }), null);
  } finally {
    cleanup();
  }
});
