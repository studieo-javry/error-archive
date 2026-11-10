/**
 * 큐 sender(drain). 영속 큐에서 보낼 수 있는 항목을 집어 서버로 전송하고, 결과에 따라 상태를 옮긴다.
 * 수집(Hook)과 분리된 경로라, 전송 장애가 개발 명령 결과를 바꾸지 않는다(#145 비차단 원칙).
 *
 * 오류 분류(설계 §3):
 *  - 네트워크/5xx/429 → 일시 실패 = 지수 backoff+jitter 재시도
 *  - 401(인증복구)·403(권한) → 재시도하되 길게 대기(타계정 재전송 아님 — 항목은 connectionId 에 묶임)
 *  - 400/409/413/422/미지원 schema → **영구 오류**(무한재시도 금지) = NEEDS_ATTENTION + 후행 BLOCKED
 *  - 이벤트 배치가 HTTP 200 이어도 개별 REJECTED 는 그 이벤트의 영구 실패로 처리
 * ACK(2xx) 뒤에만 SENT 로 표시하므로, 응답 유실 시 같은 식별자로 재전송돼 서버가 중복을 흡수한다.
 */
import { ApiError, type ApiClient } from '../apiClient.js';
import type {
  AddAttemptRequest,
  AddSolutionRequest,
  CompleteCaptureRequest,
  CreateCaptureRequest,
  EventItemRequest,
  RegisterSessionRequest,
} from '../types.js';
import { QueueStore } from './store.js';
import type { QueueItem } from './types.js';

/** 재시도 백오프 파라미터(테스트에서 주입 가능). */
export interface BackoffPolicy {
  baseMs: number;
  capMs: number;
  /** 인증/권한 대기(401/403)의 최소 간격 — 자주 두드리지 않는다. */
  authWaitMs: number;
  /** 0..1 난수(지터). 기본 Math.random. */
  jitter: () => number;
}

export const DEFAULT_BACKOFF: BackoffPolicy = {
  baseMs: 1000,
  capMs: 5 * 60 * 1000,
  authWaitMs: 60 * 1000,
  jitter: Math.random,
};

/** 분류 결과: 재시도(지연 포함) 또는 영구 실패. */
export type Classification = { retry: true; delayMs: number } | { retry: false; reason: string };

/** HTTP 상태/문제코드 → 재시도 여부·지연. attempts 로 지수 backoff(지터 포함). */
export function classify(err: unknown, attempts: number, policy: BackoffPolicy): Classification {
  const expo = () => {
    const raw = Math.min(policy.capMs, policy.baseMs * 2 ** attempts);
    return Math.floor(raw * (0.5 + policy.jitter() * 0.5)); // 50~100% 지터
  };
  if (err instanceof ApiError) {
    const { status, code } = err;
    if (status === 0) return { retry: true, delayMs: expo() }; // 네트워크
    if (status === 429 || status >= 500) return { retry: true, delayMs: expo() };
    if (status === 401 || status === 403) {
      return { retry: true, delayMs: Math.max(policy.authWaitMs, expo()) }; // 인증/권한 복구 대기
    }
    // 400/409/413/422 등 나머지 4xx = 영구(요청 자체가 거절됨 — 재시도해도 동일)
    return { retry: false, reason: `${status}${code ? ` ${code}` : ''}` };
  }
  // 알 수 없는 오류 — 보수적으로 재시도(폐기하지 않음)
  return { retry: true, delayMs: expo() };
}

export interface DrainResult {
  sent: number;
  retried: number;
  failed: number;
  blocked: number;
}

export interface SenderOptions {
  leaseMs?: number;
  backoff?: BackoffPolicy;
  /** 한 번의 drain 에서 처리할 최대 항목 수(폭주 방지). */
  maxPerDrain?: number;
  now?: () => number;
}

export class QueueSender {
  private readonly leaseMs: number;
  private readonly backoff: BackoffPolicy;
  private readonly maxPerDrain: number;
  private readonly now: () => number;

  constructor(
    private readonly store: QueueStore,
    private readonly api: ApiClient,
    opts: SenderOptions = {},
  ) {
    this.leaseMs = opts.leaseMs ?? 30_000;
    this.backoff = opts.backoff ?? DEFAULT_BACKOFF;
    this.maxPerDrain = opts.maxPerDrain ?? 200;
    this.now = opts.now ?? Date.now;
  }

  /** 집을 수 있는 항목이 없을 때까지(또는 maxPerDrain) 한 차례 비운다. 유지보수(복구·정리)도 수행. */
  async drain(): Promise<DrainResult> {
    const result: DrainResult = { sent: 0, retried: 0, failed: 0, blocked: 0 };
    this.store.reclaimExpiredLeases(this.now());
    this.store.purgeSentPayloads(this.now());

    for (let i = 0; i < this.maxPerDrain; i++) {
      const item = this.store.claim(this.leaseMs, this.now());
      if (!item) break;
      try {
        const permanentReason = await this.dispatch(item);
        if (permanentReason) {
          this.store.markPermanentFailure(item.id, permanentReason);
          result.failed++;
          result.blocked += this.countBlockedAfter();
        } else {
          this.store.markSent(item.id, this.now());
          result.sent++;
        }
      } catch (err) {
        const c = classify(err, item.attempts, this.backoff);
        if (c.retry) {
          const status = this.store.markRetry(item.id, this.now() + c.delayMs, errText(err), this.now());
          if (status === 'NEEDS_ATTENTION') result.failed++;
          else result.retried++;
        } else {
          this.store.markPermanentFailure(item.id, c.reason);
          result.failed++;
        }
      }
    }
    return result;
  }

  private countBlockedAfter(): number {
    return this.store.counts().BLOCKED;
  }

  /**
   * 항목을 해당 API 로 전송한다. 성공이면 null, **영구 거절**이면 그 사유 문자열을 돌려준다(상위가
   * NEEDS_ATTENTION 처리). 일시 오류는 ApiError 를 그대로 throw 해 상위의 classify 로 보낸다.
   */
  private async dispatch(item: QueueItem): Promise<string | null> {
    const key = item.idempotencyKey ?? undefined;
    const uuid = item.captureUuid ?? '';
    switch (item.op) {
      case 'REGISTER_SESSION':
        await this.api.registerSession(item.payload as RegisterSessionRequest, key);
        return null;
      case 'CREATE_CAPTURE':
        await this.api.createCapture(item.payload as CreateCaptureRequest, key);
        return null;
      case 'ADD_ATTEMPT':
        await this.api.addAttempt(uuid, item.payload as AddAttemptRequest, key);
        return null;
      case 'ADD_SOLUTION':
        await this.api.addSolution(uuid, item.payload as AddSolutionRequest, key);
        return null;
      case 'COMPLETE_CAPTURE':
        await this.api.completeCapture(uuid, item.payload as CompleteCaptureRequest, key);
        return null;
      case 'UPLOAD_ARTIFACT': {
        const p = item.payload as { clientArtifactId: string; filePath: string };
        await this.api.uploadArtifact(uuid, p.clientArtifactId, p.filePath);
        return null;
      }
      case 'INGEST_EVENT': {
        const res = await this.api.ingestEvents({ events: [item.payload as EventItemRequest] });
        const r = res.results[0];
        // ACCEPTED/DUPLICATE = 성공. REJECTED(충돌·과대)는 재시도해도 동일 → 영구.
        if (!r || r.result === 'REJECTED') return `event REJECTED: ${r?.reason ?? 'unknown'}`;
        return null;
      }
      case 'LINK_EVENTS': {
        const p = item.payload as { clientEventIds: string[] };
        await this.api.linkEvents(uuid, p.clientEventIds);
        return null;
      }
    }
  }
}

function errText(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
