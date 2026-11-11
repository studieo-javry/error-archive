/**
 * 설치 환경 식별(표시용). 서버의 연결 목록을 사람이 알아보게 하려고 커넥터가 신고하는 메타다.
 * **자격증명이 아니다** — 서버는 이 값을 인가에 쓰지 않고 표시·그룹에만 쓴다(위조 가능).
 *
 *  - installId: 설치당 1회 생성한 랜덤 UUID. `~/.config/error-archive-agent/install.json` 에 보관(없으면 생성).
 *    하드웨어 지문을 쓰지 않는다(프라이버시·OS 재설치 대응). 파일이 유실되면 새 설치로 인식된다(표시용이라 허용).
 *  - installLabel: 사람이 읽는 이름. 기본 hostname, `ERROR_ARCHIVE_INSTALL_LABEL` 로 지정/빈값(opt-out) 가능.
 *  - clientKind/clientVersion: 'claude-code' + 커넥터 패키지 버전.
 */
import { randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { homedir, hostname } from 'node:os';
import { dirname, join } from 'node:path';
import type { AgentClientInfo } from './apiClient.js';

export const CLIENT_KIND = 'claude-code';

function installFilePath(env: NodeJS.ProcessEnv): string {
  if (env.ERROR_ARCHIVE_INSTALL_FILE) return env.ERROR_ARCHIVE_INSTALL_FILE;
  return join(homedir(), '.config', 'error-archive-agent', 'install.json');
}

/** install.json 에서 installId 를 읽거나, 없으면 생성해 보관한다. 쓰기 실패해도 메모리 UUID 로 진행. */
function resolveInstallId(env: NodeJS.ProcessEnv): string {
  if (env.ERROR_ARCHIVE_INSTALL_ID) return env.ERROR_ARCHIVE_INSTALL_ID;
  const path = installFilePath(env);
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf8')) as { installId?: string };
    if (parsed.installId && parsed.installId.trim() !== '') return parsed.installId;
  } catch {
    /* 없음/깨짐 — 새로 생성 */
  }
  const installId = randomUUID();
  try {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, JSON.stringify({ installId }, null, 2), { mode: 0o600 });
  } catch {
    /* 쓰기 실패해도 치명적 아님(다음 실행에 다시 생성될 뿐) */
  }
  return installId;
}

/** 설치 라벨: env 지정 우선, 빈 문자열이면 opt-out(undefined), 미지정이면 hostname. */
function resolveInstallLabel(env: NodeJS.ProcessEnv): string | undefined {
  const explicit = env.ERROR_ARCHIVE_INSTALL_LABEL;
  if (explicit !== undefined) return explicit.trim() === '' ? undefined : explicit.trim();
  return hostname() || undefined;
}

/** 커넥터 패키지 버전(package.json). 실패하면 undefined(표시용이라 비치명적). */
function resolveClientVersion(): string | undefined {
  try {
    const require = createRequire(import.meta.url);
    const pkg = require('../package.json') as { version?: string };
    return pkg.version;
  } catch {
    return undefined;
  }
}

/** 서버에 신고할 설치 메타를 구성한다(표시용). */
export function resolveClientInfo(env: NodeJS.ProcessEnv = process.env): AgentClientInfo {
  return {
    kind: CLIENT_KIND,
    version: resolveClientVersion(),
    installId: resolveInstallId(env),
    installLabel: resolveInstallLabel(env),
  };
}
