#!/usr/bin/env node
/**
 * 엔트리포인트.
 *   error-archive-agent            → MCP stdio 서버(Claude Code 가 연결)
 *   error-archive-agent doctor     → 설정·연결 진단(1회성 CLI)
 *
 * ⚠️ MCP 모드에서 stdout 은 프로토콜 채널이다. 로그/진단은 반드시 stderr 로만 낸다.
 */
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { ApiError, createApiClient } from './apiClient.js';
import { ConfigError, loadConfig, maskToken } from './config.js';
import { resolveClientInfo } from './install.js';
import { createServer } from './server.js';

async function runDoctor(): Promise<number> {
  let config;
  try {
    config = loadConfig();
  } catch (err) {
    if (err instanceof ConfigError) {
      process.stderr.write(`✗ 설정 오류: ${err.message}\n`);
      return 2;
    }
    throw err;
  }
  process.stderr.write(`base URL : ${config.baseUrl}\n`);
  process.stderr.write(`token    : ${maskToken(config.token)}\n`);

  const install = resolveClientInfo();
  process.stderr.write(`install  : ${install.installLabel ?? '(label 생략)'} · ${install.kind} ${install.version ?? ''}\n`);
  const client = createApiClient(config, undefined, install);
  try {
    const me = await client.whoami();
    process.stderr.write('✓ 연결 OK\n');
    process.stderr.write(`  userId      : ${me.userId}\n`);
    process.stderr.write(`  connectionId: ${me.connectionId}\n`);
    process.stderr.write(`  scopes      : ${me.scopes.join(', ') || '(none)'}\n`);
    return 0;
  } catch (err) {
    if (err instanceof ApiError) {
      process.stderr.write(`✗ 연결 실패(status=${err.status}${err.code ? `, code=${err.code}` : ''}): ${err.message}\n`);
      return 1;
    }
    throw err;
  }
}

async function runServer(): Promise<void> {
  // 설정이 없으면 즉시 실패(MCP 핸드셰이크 전에 명확히 알림).
  const config = loadConfig();
  const client = createApiClient(config, undefined, resolveClientInfo());
  const server = createServer(client);
  const transport = new StdioServerTransport();
  await server.connect(transport);
  process.stderr.write(`error-archive-agent MCP 서버 시작(base=${config.baseUrl}, token=${maskToken(config.token)})\n`);
}

async function main(): Promise<void> {
  const command = process.argv[2];
  if (command === 'doctor') {
    process.exit(await runDoctor());
  }
  if (command && command !== 'serve') {
    process.stderr.write(`알 수 없는 명령: ${command}\n사용법: error-archive-agent [serve|doctor]\n`);
    process.exit(2);
  }
  try {
    await runServer();
  } catch (err) {
    if (err instanceof ConfigError) {
      process.stderr.write(`✗ 설정 오류: ${err.message}\n설정을 확인하려면: error-archive-agent doctor\n`);
      process.exit(2);
    }
    throw err;
  }
}

main().catch((err) => {
  process.stderr.write(`치명적 오류: ${err instanceof Error ? err.stack ?? err.message : String(err)}\n`);
  process.exit(1);
});
