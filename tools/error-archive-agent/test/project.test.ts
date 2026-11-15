import assert from 'node:assert/strict';
import { test } from 'node:test';
import { localProjectKey, normalizeRemote } from '../src/project.js';

test('normalizeRemote: git@ 와 https 는 같은 키로 정규화', () => {
  const expected = 'github.com/org/repo';
  assert.equal(normalizeRemote('git@github.com:org/repo.git'), expected);
  assert.equal(normalizeRemote('https://github.com/org/repo.git'), expected);
  assert.equal(normalizeRemote('https://github.com/org/repo'), expected);
  assert.equal(normalizeRemote('ssh://git@github.com/org/repo.git'), expected);
  assert.equal(normalizeRemote('https://GitHub.com/org/repo/'), expected); // host 소문자, 후행 슬래시 제거
});

test('normalizeRemote: 중첩 경로(gitlab subgroup)·토큰 포함 URL', () => {
  assert.equal(normalizeRemote('git@gitlab.com:group/sub/repo.git'), 'gitlab.com/group/sub/repo');
  assert.equal(normalizeRemote('https://x-token:secret@github.com/org/repo.git'), 'github.com/org/repo');
});

test('normalizeRemote: 빈/비정상 입력은 null', () => {
  assert.equal(normalizeRemote(null), null);
  assert.equal(normalizeRemote(undefined), null);
  assert.equal(normalizeRemote(''), null);
  assert.equal(normalizeRemote('   '), null);
  assert.equal(normalizeRemote('not a url'), null);
});

test('localProjectKey: 경로 해시 포함·결정적·basename 포함(동명 다른폴더 충돌 방지)', () => {
  const a = localProjectKey('/Users/me/dev/checkout');
  const b = localProjectKey('/Users/other/work/checkout');
  assert.match(a, /^local:[0-9a-f]{12}:checkout$/);
  assert.equal(a, localProjectKey('/Users/me/dev/checkout')); // 결정적
  assert.notEqual(a, b); // 같은 basename 이어도 경로 다르면 다른 키
});
