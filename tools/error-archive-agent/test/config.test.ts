import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ConfigError, loadConfig, maskToken } from '../src/config.js';

const noFile = () => {
  throw new Error('ENOENT');
};

test('환경변수에서 설정을 구성하고 baseUrl 끝 슬래시를 제거한다', () => {
  const cfg = loadConfig({
    env: { ERROR_ARCHIVE_BASE_URL: 'https://api.error-archive.com/', ERROR_ARCHIVE_AGENT_TOKEN: 'eaa_abcd1234' },
    readFile: noFile,
  });
  assert.equal(cfg.baseUrl, 'https://api.error-archive.com');
  assert.equal(cfg.token, 'eaa_abcd1234');
});

test('토큰 누락이면 ConfigError', () => {
  assert.throws(
    () => loadConfig({ env: { ERROR_ARCHIVE_BASE_URL: 'https://x.com' }, readFile: noFile }),
    ConfigError,
  );
});

test('토큰 접두사가 eaa_ 가 아니면 ConfigError', () => {
  assert.throws(
    () => loadConfig({ env: { ERROR_ARCHIVE_BASE_URL: 'https://x.com', ERROR_ARCHIVE_AGENT_TOKEN: 'nope_123' }, readFile: noFile }),
    ConfigError,
  );
});

test('baseUrl 이 http(s) 가 아니면 ConfigError', () => {
  assert.throws(
    () => loadConfig({ env: { ERROR_ARCHIVE_BASE_URL: 'ftp://x.com', ERROR_ARCHIVE_AGENT_TOKEN: 'eaa_1' }, readFile: noFile }),
    ConfigError,
  );
});

test('환경변수가 없으면 설정 파일로 폴백한다', () => {
  const cfg = loadConfig({
    env: {},
    configPath: '/fake/config.json',
    readFile: () => JSON.stringify({ baseUrl: 'https://file.error-archive.com', token: 'eaa_fromfile' }),
  });
  assert.equal(cfg.baseUrl, 'https://file.error-archive.com');
  assert.equal(cfg.token, 'eaa_fromfile');
});

test('환경변수가 설정 파일보다 우선한다', () => {
  const cfg = loadConfig({
    env: { ERROR_ARCHIVE_AGENT_TOKEN: 'eaa_env' },
    configPath: '/fake/config.json',
    readFile: () => JSON.stringify({ baseUrl: 'https://file.com', token: 'eaa_file' }),
  });
  assert.equal(cfg.baseUrl, 'https://file.com'); // 파일에서
  assert.equal(cfg.token, 'eaa_env'); // env 가 우선
});

test('CLAUDE_CODE_SESSION_ID 가 sessionId 로 배선된다(Claude Code 자동 주입)', () => {
  const cfg = loadConfig({
    env: {
      ERROR_ARCHIVE_BASE_URL: 'https://x.com', ERROR_ARCHIVE_AGENT_TOKEN: 'eaa_1',
      CLAUDE_CODE_SESSION_ID: 'cc-sess-123',
    },
    readFile: noFile,
  });
  assert.equal(cfg.sessionId, 'cc-sess-123');
});

test('ERROR_ARCHIVE_SESSION_ID 가 CLAUDE_CODE_SESSION_ID 보다 우선한다', () => {
  const cfg = loadConfig({
    env: {
      ERROR_ARCHIVE_BASE_URL: 'https://x.com', ERROR_ARCHIVE_AGENT_TOKEN: 'eaa_1',
      ERROR_ARCHIVE_SESSION_ID: 'explicit', CLAUDE_CODE_SESSION_ID: 'cc-sess-123',
    },
    readFile: noFile,
  });
  assert.equal(cfg.sessionId, 'explicit');
});

test('maskToken 은 원문을 노출하지 않는다', () => {
  const masked = maskToken('eaa_supersecretvalue');
  assert.ok(!masked.includes('supersecret'));
  assert.ok(masked.startsWith('eaa_'));
});
