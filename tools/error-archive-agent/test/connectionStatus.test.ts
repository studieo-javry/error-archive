import assert from 'node:assert/strict';
import { test } from 'node:test';
import { describeConnection } from '../src/connectionStatus.js';

test('describeConnection — 연결됨', () => {
  const r = describeConnection({ recordStatus: 'APPROVED', connected: true, remoteChanged: false, since: '2026-11-17T00:00:00.000Z' });
  assert.equal(r.state, 'CONNECTED');
  assert.ok(r.label.includes('연결됨'));
});

test('describeConnection — remote 변경(재연결 필요)', () => {
  const r = describeConnection({ recordStatus: 'APPROVED', connected: false, remoteChanged: true, since: null });
  assert.equal(r.state, 'REMOTE_CHANGED');
});

test('describeConnection — 보류/거절/미연결', () => {
  assert.equal(describeConnection({ recordStatus: 'SNOOZED', connected: false, remoteChanged: false, since: null }).state, 'SNOOZED');
  assert.equal(describeConnection({ recordStatus: 'DECLINED', connected: false, remoteChanged: false, since: null }).state, 'DECLINED');
  assert.equal(describeConnection({ recordStatus: null, connected: false, remoteChanged: false, since: null }).state, 'UNAPPROVED');
});

test('connected 가 remoteChanged 보다 우선', () => {
  // 방어: connected=true 면 remoteChanged 무시하고 CONNECTED
  const r = describeConnection({ recordStatus: 'APPROVED', connected: true, remoteChanged: true, since: null });
  assert.equal(r.state, 'CONNECTED');
});
