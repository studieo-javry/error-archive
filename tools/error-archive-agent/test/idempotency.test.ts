import assert from 'node:assert/strict';
import { test } from 'node:test';
import { newIdempotencyKey, newUuid } from '../src/idempotency.js';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

test('newUuid 는 UUID v4 형식', () => {
  assert.match(newUuid(), UUID_RE);
});

test('newIdempotencyKey 는 매번 다른 값', () => {
  assert.notEqual(newIdempotencyKey(), newIdempotencyKey());
});
