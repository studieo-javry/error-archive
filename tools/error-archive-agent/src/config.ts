/**
 * 커넥터 설정 로딩/검증. 우선순위: 환경변수 > 설정 파일.
 *
 *  - ERROR_ARCHIVE_BASE_URL   게이트웨이 공개 base URL (예: https://api.error-archive.com)
 *  - ERROR_ARCHIVE_AGENT_TOKEN 연결 토큰(`eaa_...`) — 웹 UI(설정 › 에이전트 연결)에서 1회 발급.
 *
 * 설정 파일(선택): $ERROR_ARCHIVE_CONFIG 또는 ~/.config/error-archive-agent/config.json
 *   { "baseUrl": "...", "token": "eaa_..." }
 *
 * 토큰은 비밀이다. 로그/에러 메시지에 원문을 싣지 않는다(maskToken 사용).
 */
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

export interface AgentConfig {
  baseUrl: string;
  token: string;
}

/** 연결 토큰 접두사(iam 발급 규칙과 동일). */
export const TOKEN_PREFIX = 'eaa_';

export class ConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ConfigError';
  }
}

/** 토큰을 로그용으로 마스킹(`eaa_1234…`). 원문은 절대 노출하지 않는다. */
export function maskToken(token: string): string {
  if (!token) return '(none)';
  const head = token.slice(0, TOKEN_PREFIX.length + 4);
  return `${head}…(${token.length} chars)`;
}

interface LoadOptions {
  /** 테스트/주입용. 기본 process.env. */
  env?: NodeJS.ProcessEnv;
  /** 테스트/주입용 파일 리더. 기본 fs.readFileSync(utf8). */
  readFile?: (path: string) => string;
  /** 설정 파일 경로 오버라이드(기본: env 또는 ~/.config/...). */
  configPath?: string;
}

function defaultConfigPath(env: NodeJS.ProcessEnv): string {
  return env.ERROR_ARCHIVE_CONFIG ?? join(homedir(), '.config', 'error-archive-agent', 'config.json');
}

/** 설정 파일을 읽어 부분 설정을 반환(없거나 깨지면 무시하고 {}). */
function readConfigFile(path: string, readFile: (p: string) => string): Partial<AgentConfig> {
  let raw: string;
  try {
    raw = readFile(path);
  } catch {
    return {}; // 파일 없음 — 환경변수만으로 동작 가능
  }
  try {
    const parsed = JSON.parse(raw) as Partial<AgentConfig>;
    return {
      baseUrl: typeof parsed.baseUrl === 'string' ? parsed.baseUrl : undefined,
      token: typeof parsed.token === 'string' ? parsed.token : undefined,
    };
  } catch {
    throw new ConfigError(`설정 파일 JSON 파싱 실패: ${path}`);
  }
}

function validateBaseUrl(raw: string): string {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new ConfigError(`ERROR_ARCHIVE_BASE_URL 이 올바른 URL 이 아닙니다: ${raw}`);
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new ConfigError(`ERROR_ARCHIVE_BASE_URL 은 http(s) 여야 합니다: ${raw}`);
  }
  // 끝 슬래시 제거(경로 조합 일관성).
  return raw.replace(/\/+$/, '');
}

function validateToken(raw: string): string {
  const token = raw.trim();
  if (!token.startsWith(TOKEN_PREFIX)) {
    throw new ConfigError(`에이전트 토큰은 "${TOKEN_PREFIX}" 로 시작해야 합니다(연결 토큰이 아닌 값으로 보입니다).`);
  }
  return token;
}

/**
 * 환경변수 + 설정 파일에서 설정을 구성·검증한다. 누락/형식 오류면 ConfigError.
 */
export function loadConfig(options: LoadOptions = {}): AgentConfig {
  const env = options.env ?? process.env;
  const readFile = options.readFile ?? ((p: string) => readFileSync(p, 'utf8'));
  const path = options.configPath ?? defaultConfigPath(env);

  const file = readConfigFile(path, readFile);

  const baseUrlRaw = env.ERROR_ARCHIVE_BASE_URL ?? file.baseUrl;
  const tokenRaw = env.ERROR_ARCHIVE_AGENT_TOKEN ?? file.token;

  if (!baseUrlRaw) {
    throw new ConfigError('ERROR_ARCHIVE_BASE_URL 이 설정되지 않았습니다(환경변수 또는 설정 파일).');
  }
  if (!tokenRaw) {
    throw new ConfigError('ERROR_ARCHIVE_AGENT_TOKEN 이 설정되지 않았습니다(환경변수 또는 설정 파일).');
  }

  return {
    baseUrl: validateBaseUrl(baseUrlRaw),
    token: validateToken(tokenRaw),
  };
}
