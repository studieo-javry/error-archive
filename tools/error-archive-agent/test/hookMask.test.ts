import assert from 'node:assert/strict';
import { test } from 'node:test';
import { REDACTION, maskAndCap, maskString, maskValue } from '../src/hooks/mask.js';

test('maskString: 알려진 토큰 패턴을 가린다', () => {
  assert.equal(maskString('token is eaa_abcdef123456 ok'), `token is ${REDACTION} ok`);
  assert.equal(maskString('Authorization: Bearer abc.def-123'), `Authorization: ${REDACTION}`);
  assert.match(maskString('key sk-0123456789abcdef0123'), new RegExp(REDACTION));
  assert.match(maskString('aws AKIAIOSFODNN7EXAMPLE here'), new RegExp(REDACTION));
});

test('maskValue: 민감 키의 값은 통째로 가린다', () => {
  const out = maskValue({ password: 'hunter2', apiKey: 'x', nested: { secret_token: 'y', ok: 'keep' } }) as Record<string, unknown>;
  assert.equal(out.password, REDACTION);
  assert.equal(out.apiKey, REDACTION);
  const nested = out.nested as Record<string, unknown>;
  assert.equal(nested.secret_token, REDACTION);
  assert.equal(nested.ok, 'keep');
});

test('maskValue: 비민감 키의 문자열은 패턴만 가린다(나머지 보존)', () => {
  const out = maskValue({ command: 'curl -H "Authorization: Bearer tok123" https://x' }) as Record<string, unknown>;
  assert.match(out.command as string, new RegExp(REDACTION));
  assert.match(out.command as string, /curl -H/); // 명령 구조는 남는다
});

test('maskValue: 배열·원시값·null 처리', () => {
  assert.deepEqual(maskValue(['eaa_12345678', 1, null, true]), [REDACTION, 1, null, true]);
});

test('maskAndCap: 64 KiB 이하는 그대로', () => {
  const r = maskAndCap({ a: 'x'.repeat(100) });
  assert.equal(r.truncated, false);
});

test('maskAndCap: 64 KiB 초과는 잘라내고 truncated=true', () => {
  const r = maskAndCap({ blob: 'x'.repeat(70_000) });
  assert.equal(r.truncated, true);
  assert.match(JSON.stringify(r.payload), /too large/);
});

test('maskAndCap: PEM 개인키는 가려진다', () => {
  const pem = '-----BEGIN PRIVATE KEY-----\nAAAA\nBBBB\n-----END PRIVATE KEY-----';
  const r = maskAndCap({ note: `here ${pem} done` });
  assert.ok(!JSON.stringify(r.payload).includes('AAAA'));
});
