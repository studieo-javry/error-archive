// 수집 payload 안전 처리(P1-5 핵심) — 시크릿 마스킹 + 크기 절단. 서버 제약: payload ≤ 64 KiB.
import type { JsonValue } from './types.js';

export const MAX_PAYLOAD_BYTES = 64 * 1024;
const REDACTED = '[REDACTED]';

/** 흔한 시크릿 패턴. 과탐이 과소탐보다 안전(수집 데이터라 레다크션이 보수적이어도 OK). */
const SECRET_PATTERNS: RegExp[] = [
  /eaa_[A-Za-z0-9_-]{8,}/g, // 커넥터 연결 토큰
  /gh[pousr]_[A-Za-z0-9]{20,}/g, // GitHub 토큰
  /sk-[A-Za-z0-9]{20,}/g, // OpenAI 류 키
  /AKIA[0-9A-Z]{16}/g, // AWS access key id
  /-----BEGIN[A-Z ]*PRIVATE KEY-----[\s\S]*?-----END[A-Z ]*PRIVATE KEY-----/g, // PEM
  // key=value / "token": "..." 형태의 민감 키
  /((?:password|passwd|secret|token|api[_-]?key|authorization|bearer|access[_-]?key)["']?\s*[:=]\s*["']?)[^\s"'&]+/gi,
];

/** 문자열에서 시크릿을 레다크션. key=value 패턴은 키는 남기고 값만 가린다. */
export function redactSecrets(s: string): string {
  let out = s;
  for (const re of SECRET_PATTERNS) {
    out = out.replace(re, (match, prefix?: string) => (prefix != null ? `${prefix}${REDACTED}` : REDACTED));
  }
  return out;
}

/** JSON 값 전체를 재귀적으로 마스킹(문자열만 레다크션 대상). */
export function maskValue(v: JsonValue): JsonValue {
  if (typeof v === 'string') return redactSecrets(v);
  if (Array.isArray(v)) return v.map(maskValue);
  if (v && typeof v === 'object') {
    const out: { [k: string]: JsonValue } = {};
    for (const [k, val] of Object.entries(v)) out[k] = maskValue(val as JsonValue);
    return out;
  }
  return v;
}

/**
 * 마스킹 후 직렬화 크기가 64 KiB 를 넘으면 절단 마커로 대체(초과 payload 는 서버가 REJECT 하므로 사전 방지).
 * @returns payload(마스킹·필요 시 절단된 JSON) + truncated 플래그.
 */
export function maskAndTruncate(v: JsonValue): { payload: JsonValue; truncated: boolean } {
  const masked = maskValue(v);
  const json = JSON.stringify(masked);
  if (Buffer.byteLength(json, 'utf8') <= MAX_PAYLOAD_BYTES) return { payload: masked, truncated: false };
  const preview = Buffer.from(json, 'utf8').subarray(0, MAX_PAYLOAD_BYTES - 256).toString('utf8');
  return { payload: { _truncated: true, previewBytes: MAX_PAYLOAD_BYTES - 256, preview }, truncated: true };
}
