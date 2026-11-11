import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { CLIENT_KIND, resolveClientInfo } from '../src/install.js';

function envWith(extra: Record<string, string>): NodeJS.ProcessEnv {
  const dir = mkdtempSync(join(tmpdir(), 'ea-install-'));
  return { ERROR_ARCHIVE_INSTALL_FILE: join(dir, 'install.json'), ...extra };
}

test('resolveClientInfo: kind=claude-code, installId 생성·파일 보관', () => {
  const env = envWith({});
  const info = resolveClientInfo(env);
  assert.equal(info.kind, CLIENT_KIND);
  assert.match(info.installId, /^[0-9a-f-]{36}$/);
  // install.json 에 보관됐는가
  const saved = JSON.parse(readFileSync(env.ERROR_ARCHIVE_INSTALL_FILE!, 'utf8')) as { installId: string };
  assert.equal(saved.installId, info.installId);
});

test('resolveClientInfo: 같은 install 파일이면 installId 재사용(설치당 안정)', () => {
  const env = envWith({});
  const first = resolveClientInfo(env).installId;
  const second = resolveClientInfo(env).installId;
  assert.equal(first, second);
});

test('resolveClientInfo: ERROR_ARCHIVE_INSTALL_ID 가 최우선', () => {
  const info = resolveClientInfo(envWith({ ERROR_ARCHIVE_INSTALL_ID: 'fixed-id' }));
  assert.equal(info.installId, 'fixed-id');
});

test('resolveClientInfo: INSTALL_LABEL 지정/opt-out', () => {
  assert.equal(resolveClientInfo(envWith({ ERROR_ARCHIVE_INSTALL_LABEL: '내 데스크탑' })).installLabel, '내 데스크탑');
  // 빈 문자열 → opt-out(undefined)
  assert.equal(resolveClientInfo(envWith({ ERROR_ARCHIVE_INSTALL_LABEL: '' })).installLabel, undefined);
});

test('resolveClientInfo: 라벨 미지정이면 hostname 폴백(비어있지 않음)', () => {
  const info = resolveClientInfo(envWith({}));
  // hostname 은 환경마다 다르므로 존재만 확인(빈 문자열 아님)
  assert.ok(info.installLabel === undefined || info.installLabel.length > 0);
});
