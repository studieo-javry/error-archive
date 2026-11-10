import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { resolveWithinBoundary } from '../src/evidence/boundary.js';

function sandbox() {
  const dir = mkdtempSync(join(tmpdir(), 'ea-bnd-'));
  const root = join(dir, 'project');
  const outside = join(dir, 'outside');
  mkdirSync(root);
  mkdirSync(outside);
  return { dir, root, outside };
}

test('경계 내부 파일은 정규 경로 반환', () => {
  const { root } = sandbox();
  const f = join(root, 'log.txt');
  writeFileSync(f, 'x');
  assert.ok(resolveWithinBoundary([root], f));
});

test('경계 밖 파일은 null', () => {
  const { root, outside } = sandbox();
  const f = join(outside, 'secret.txt');
  writeFileSync(f, 'x');
  assert.equal(resolveWithinBoundary([root], f), null);
});

test('심볼릭 링크가 밖을 가리키면 차단(이탈 방지)', () => {
  const { root, outside } = sandbox();
  const target = join(outside, 'secret.txt');
  writeFileSync(target, 'top secret');
  const link = join(root, 'inside-link.txt'); // 루트 안에 있지만 밖을 가리키는 링크
  symlinkSync(target, link);
  assert.equal(resolveWithinBoundary([root], link), null);
});

test('존재하지 않는 파일은 null', () => {
  const { root } = sandbox();
  assert.equal(resolveWithinBoundary([root], join(root, 'nope.txt')), null);
});

test('여러 루트 중 하나에 속하면 허용', () => {
  const { root, outside } = sandbox();
  const f = join(outside, 'a.txt');
  writeFileSync(f, 'x');
  assert.ok(resolveWithinBoundary([root, outside], f));
});
