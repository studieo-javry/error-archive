/**
 * 식별자 생성. captureUuid/clientId 는 클라이언트가 부여하며, 재전송에서도 같은 값을 유지해야
 * 서버의 UNIQUE 제약·멱등이 중복 생성을 막는다.
 *
 * Idempotency-Key 는 '같은 논리 연산의 재시도'를 서버가 알아보게 하는 키다. 한 번의 MCP 호출은
 * 보통 1회성이지만, 네트워크 재시도를 안전하게 하려면 호출 측이 같은 키를 재사용해야 한다.
 */
import { createHash, randomUUID } from 'node:crypto';
import { basename } from 'node:path';

/** UUID v4. captureUuid 등 클라이언트 부여 식별자용. */
export function newUuid(): string {
  return randomUUID();
}

/** 새 Idempotency-Key(명시 키가 없을 때의 기본값). */
export function newIdempotencyKey(): string {
  return randomUUID();
}

/**
 * 근거 파일의 기본 clientArtifactId. 파일명만 쓰면 경로가 다른 동명 파일(a/error.log, b/error.log)이
 * 같은 ID 를 받아, 서버가 내용 해시를 비교해 409(ARTIFACT_HASH_MISMATCH)를 낼 수 있다. 그래서
 * 파일명 뒤에 전체 경로 해시 앞자리를 붙인다 — 같은 경로(재시도)는 같은 ID(서버가 dedup), 다른
 * 경로는 다른 ID(거짓 충돌 없음).
 */
export function defaultArtifactId(filePath: string): string {
  const name = basename(filePath) || 'artifact';
  const suffix = createHash('sha256').update(filePath).digest('hex').slice(0, 8);
  return `${name}-${suffix}`;
}

/**
 * 논리 연산(operation)과 안정 식별자(anchor)로 결정론적 Idempotency-Key 를 만든다. 호출 측이 같은
 * 식별자(captureUuid/clientAttemptId/clientSolutionId)로 재시도하면 같은 키가 나와, 서버가 재시도를
 * 하나로 합친다(중복 생성 방지). 임의 키(newIdempotencyKey)는 매번 달라 이 보장을 못 준다.
 */
export function idempotencyKeyFor(operation: string, anchor: string): string {
  return createHash('sha256').update(`${operation}\u0000${anchor}`).digest('hex');
}
