// 큐 → 서버 전송(P1-3 sender). 배치(≤100건·≤~900KiB)로 /agent/events/batch 에 보내고 결과 반영.
//  - ACCEPTED/DUPLICATE → 큐에서 제거.  REJECTED → dead-letter 로 이동.
//  - 네트워크/5xx(throw) → 중단하고 잔여는 pending 유지(다음 flush 재시도 — 오프라인 안전).

import type { ApiClient } from './apiClient.js';
import { isCollectionApproved } from './consentIndex.js';
import type { EventQueue } from './eventQueue.js';
import type { EventItemRequest, QueuedEvent } from './types.js';

export interface FlushResult {
  sent: number;
  rejected: number;
  /** (Phase3) 승인 철회 등으로 전송 않고 큐에서 드랍한 건수. */
  dropped: number;
  pending: number;
}

/** 전송 직전 로컬 전용 메타(_local)를 제거한 서버 payload. */
function stripLocal(e: QueuedEvent): EventItemRequest {
  const { _local: _omit, ...rest } = e;
  return rest;
}

const MAX_BATCH = 100;
const MAX_BATCH_BYTES = 900 * 1024; // 서버 본문 상한 1MiB 보다 보수적으로

/** 건수 + 누적 바이트로 배치 분할. */
function toBatches(events: QueuedEvent[]): QueuedEvent[][] {
  const batches: QueuedEvent[][] = [];
  let cur: QueuedEvent[] = [];
  let bytes = 0;
  for (const ev of events) {
    const size = Buffer.byteLength(JSON.stringify(ev), 'utf8');
    if (cur.length >= MAX_BATCH || (cur.length > 0 && bytes + size > MAX_BATCH_BYTES)) {
      batches.push(cur);
      cur = [];
      bytes = 0;
    }
    cur.push(ev);
    bytes += size;
  }
  if (cur.length > 0) batches.push(cur);
  return batches;
}

export interface FlushOptions {
  /** (Phase3) 토큰-ns 동의 인덱스로 각 이벤트의 승인을 전송 직전 재검사. 미설정이면 재검사 생략(하위호환). */
  consentNamespace?: string;
  consentPath?: string;
}

export async function flushQueue(client: ApiClient, queue: EventQueue, opts: FlushOptions = {}): Promise<FlushResult> {
  const all = queue.readAll();
  if (all.length === 0) return { sent: 0, rejected: 0, dropped: 0, pending: 0 };

  // (Phase3) 승인 재검사 — 적재 후 연결이 해제된 프로젝트의 잔여 이벤트는 전송하지 않고 드랍한다.
  //  _local(로컬 메타)이 있는 이벤트만 검사(그 외 레거시 이벤트는 기존대로 전송). fail-closed.
  let dropped = 0;
  let pending: QueuedEvent[] = all;
  if (opts.consentNamespace) {
    const ns = opts.consentNamespace;
    const drop = new Set<string>();
    pending = all.filter((e) => {
      if (!e._local) return true;
      if (isCollectionApproved(ns, e._local.gitRoot, e._local.remote, { path: opts.consentPath })) return true;
      drop.add(e.clientEventId);
      return false;
    });
    if (drop.size > 0) {
      queue.remove(drop); // 전송 없이 큐에서만 제거(dead-letter 아님 — 거절이 아니라 동의 철회)
      dropped = drop.size;
    }
    if (pending.length === 0) return { sent: 0, rejected: 0, dropped, pending: queue.size() };
  }

  let sent = 0;
  let rejected = 0;
  for (const batch of toBatches(pending)) {
    let response;
    try {
      response = await client.ingestEvents({ events: batch.map(stripLocal) }); // _local 은 서버로 보내지 않음
    } catch {
      break; // 네트워크/서버 오류 → 잔여는 pending 유지, 다음 flush 에서 재시도
    }
    const done = new Set<string>();
    const rej: QueuedEvent[] = [];
    for (const r of response.results) {
      if (r.result === 'ACCEPTED' || r.result === 'DUPLICATE') {
        done.add(r.clientEventId);
        sent += 1;
      } else if (r.result === 'REJECTED') {
        done.add(r.clientEventId);
        const ev = batch.find((e) => e.clientEventId === r.clientEventId);
        if (ev) rej.push(ev);
        rejected += 1;
      }
    }
    queue.remove(done, rej);
  }
  return { sent, rejected, dropped, pending: queue.size() };
}
