/**
 * 식별자 생성. captureUuid/clientId 는 클라이언트가 부여하며, 재전송에서도 같은 값을 유지해야
 * 서버의 UNIQUE 제약·멱등이 중복 생성을 막는다.
 *
 * Idempotency-Key 는 '같은 논리 연산의 재시도'를 서버가 알아보게 하는 키다. 한 번의 MCP 호출은
 * 보통 1회성이지만, 네트워크 재시도를 안전하게 하려면 호출 측이 같은 키를 재사용해야 한다.
 */
import { randomUUID } from 'node:crypto';

/** UUID v4. captureUuid 등 클라이언트 부여 식별자용. */
export function newUuid(): string {
  return randomUUID();
}

/** 새 Idempotency-Key(명시 키가 없을 때의 기본값). */
export function newIdempotencyKey(): string {
  return randomUUID();
}
