/**
 * Hook payload 마스킹(전송 전 비밀정보 제거) + 크기 상한. **마스킹이 기본이고, 실패 시 전송 보류**가
 * 원칙(#145 §4·§5)이지만 여기서는 1차 방어(알려진 토큰·키 패턴, 민감 키 이름)만 담당한다.
 * 로그/본문에 payload·토큰 원문이 남지 않도록 한다.
 *
 * 두 축으로 가린다:
 *  1) **키 이름** 휴리스틱: token/secret/password/authorization/api_key/credential/private_key 등은 값 전체를 가린다.
 *  2) **값 패턴**: eaa_·Bearer·sk-·AWS AKIA·PEM 블록 등은 문자열 어디에 있든 가린다(Bash 명령에 섞여도).
 */

const SENSITIVE_KEY = /(token|secret|password|passwd|api[_-]?key|authorization|auth|credential|private[_-]?key|access[_-]?key)/i;

const VALUE_PATTERNS: RegExp[] = [
  /eaa_[A-Za-z0-9_-]{8,}/g, // error-archive 연결 토큰
  /\bBearer\s+[A-Za-z0-9._-]+/gi, // Authorization 헤더
  /\bsk-[A-Za-z0-9]{16,}/g, // OpenAI/유사 비밀키
  /\bAKIA[0-9A-Z]{16}\b/g, // AWS access key id
  /\bghp_[A-Za-z0-9]{20,}/g, // GitHub PAT
  /\bgh[opsu]_[A-Za-z0-9]{20,}/g, // GitHub 기타 토큰
  /-----BEGIN[A-Z ]*PRIVATE KEY-----[\s\S]*?-----END[A-Z ]*PRIVATE KEY-----/g, // PEM
];

export const REDACTION = '«redacted»';

/** 문자열 안의 알려진 비밀 패턴을 가린다. */
export function maskString(value: string): string {
  let out = value;
  for (const re of VALUE_PATTERNS) out = out.replace(re, REDACTION);
  return out;
}

/**
 * JSON 값을 재귀적으로 마스킹한다. 민감 키의 값은 통째로 가리고, 나머지 문자열은 패턴만 가린다.
 * 순환 참조는 '«circular»' 로 끊는다(정규화 입력에 없겠지만 방어).
 */
export function maskValue(value: unknown, seen = new WeakSet<object>()): unknown {
  if (typeof value === 'string') return maskString(value);
  if (value === null || typeof value !== 'object') return value;
  if (seen.has(value)) return '«circular»';
  seen.add(value);
  if (Array.isArray(value)) return value.map((v) => maskValue(v, seen));
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
    out[k] = SENSITIVE_KEY.test(k) ? REDACTION : maskValue(v, seen);
  }
  return out;
}

/** 이벤트 payload 상한(서버와 동일: 64 KiB). */
export const MAX_PAYLOAD_BYTES = 64 * 1024;

export interface CappedPayload {
  payload: unknown;
  truncated: boolean;
}

/**
 * 마스킹 후 64 KiB 를 넘으면 **잘라내고 truncated=true** 로 표시한다(서버가 과대 payload 를 REJECTED
 * 하지 않도록). 큰 근거는 payload 대신 첨부로 올리는 게 원칙 — 여기선 안전한 축약만 한다.
 */
export function maskAndCap(raw: unknown): CappedPayload {
  const masked = maskValue(raw);
  const json = JSON.stringify(masked ?? null);
  if (Buffer.byteLength(json, 'utf8') <= MAX_PAYLOAD_BYTES) return { payload: masked, truncated: false };
  // 상한 초과 — 원본 대신 요약 스텁으로 대체(내용은 첨부 경로로 가야 함).
  return {
    payload: { truncated: true, byteSize: Buffer.byteLength(json, 'utf8'), note: 'payload too large — raw dropped, attach as evidence instead' },
    truncated: true,
  };
}
