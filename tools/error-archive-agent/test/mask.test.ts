import assert from 'node:assert/strict';
import { test } from 'node:test';
import { maskAndTruncate, maskValue, redactSecrets } from '../src/mask.js';

test('redactSecrets: 토큰·키 패턴 레다크션', () => {
  assert.match(redactSecrets('token is eaa_abcdef12345678'), /\[REDACTED\]/);
  assert.match(redactSecrets('ghp_0123456789abcdef0123456789abcdef0123'), /\[REDACTED\]/);
  assert.match(redactSecrets('password: hunter2secret'), /password[^]*\[REDACTED\]/i);
  assert.equal(redactSecrets('normal log line'), 'normal log line');
});

test('maskValue: 중첩 객체/배열의 문자열만 재귀 레다크션', () => {
  const masked = maskValue({ a: 'eaa_deadbeef12345678', b: [1, 'ghp_0123456789abcdef0123456789abcdef0123'], c: 42 });
  assert.match(JSON.stringify(masked), /\[REDACTED\]/);
  assert.equal((masked as { c: number }).c, 42);
});

test('maskAndTruncate: 64KiB 초과는 절단 마커로', () => {
  const small = maskAndTruncate({ msg: 'hi' });
  assert.equal(small.truncated, false);

  const big = maskAndTruncate({ blob: 'x'.repeat(70 * 1024) });
  assert.equal(big.truncated, true);
  assert.equal((big.payload as { _truncated?: boolean })._truncated, true);
  assert.ok(Buffer.byteLength(JSON.stringify(big.payload), 'utf8') <= 64 * 1024);
});
