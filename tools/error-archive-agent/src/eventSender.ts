// 큐 → 서버 전송(P1-3 sender). 배치(≤100건·≤~900KiB)로 /agent/events/batch 에 보내고 결과 반영.
//  - ACCEPTED/DUPLICATE → 큐에서 제거.  REJECTED → dead-letter 로 이동.
//  - 네트워크/5xx(throw) → 중단하고 잔여는 pending 유지(다음 flush 재시도 — 오프라인 안전).

import type { ApiClient } from './apiClient.js';
import type { EventQueue } from './eventQueue.js';
import type { EventItemRequest } from './types.js';

export interface FlushResult {
  sent: number;
  rejected: number;
  pending: number;
}

const MAX_BATCH = 100;
const MAX_BATCH_BYTES = 900 * 1024; // 서버 본문 상한 1MiB 보다 보수적으로

/** 건수 + 누적 바이트로 배치 분할. */
function toBatches(events: EventItemRequest[]): EventItemRequest[][] {
  const batches: EventItemRequest[][] = [];
  let cur: EventItemRequest[] = [];
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

export async function flushQueue(client: ApiClient, queue: EventQueue): Promise<FlushResult> {
  const pending = queue.readAll();
  if (pending.length === 0) return { sent: 0, rejected: 0, pending: 0 };

  let sent = 0;
  let rejected = 0;
  for (const batch of toBatches(pending)) {
    let response;
    try {
      response = await client.ingestEvents({ events: batch });
    } catch {
      break; // 네트워크/서버 오류 → 잔여는 pending 유지, 다음 flush 에서 재시도
    }
    const done = new Set<string>();
    const rej: EventItemRequest[] = [];
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
  return { sent, rejected, pending: queue.size() };
}
