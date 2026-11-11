import assert from 'node:assert/strict';
import { test } from 'node:test';
import { defaultArtifactId, idempotencyKeyFor, newIdempotencyKey, newUuid } from '../src/idempotency.js';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

test('newUuid 는 UUID v4 형식', () => {
  assert.match(newUuid(), UUID_RE);
});

test('newIdempotencyKey 는 매번 다른 값', () => {
  assert.notEqual(newIdempotencyKey(), newIdempotencyKey());
});

test('defaultArtifactId: 같은 경로는 같은 ID(재시도 안정)', () => {
  assert.equal(defaultArtifactId('logs/error.log'), defaultArtifactId('logs/error.log'));
});

test('defaultArtifactId: 파일명이 같아도 경로가 다르면 다른 ID(거짓 충돌 방지)', () => {
  const a = defaultArtifactId('a/error.log');
  const b = defaultArtifactId('b/error.log');
  assert.notEqual(a, b);
  assert.ok(a.startsWith('error.log-'));
  assert.ok(b.startsWith('error.log-'));
});

test('idempotencyKeyFor: 같은 (연산,식별자)는 결정론적으로 같은 키', () => {
  assert.equal(idempotencyKeyFor('capture.create', 'uuid-1'), idempotencyKeyFor('capture.create', 'uuid-1'));
});

test('idempotencyKeyFor: 연산 또는 식별자가 다르면 다른 키', () => {
  assert.notEqual(idempotencyKeyFor('capture.create', 'uuid-1'), idempotencyKeyFor('capture.create', 'uuid-2'));
  assert.notEqual(idempotencyKeyFor('attempt.add', 'x'), idempotencyKeyFor('solution.add', 'x'));
});
