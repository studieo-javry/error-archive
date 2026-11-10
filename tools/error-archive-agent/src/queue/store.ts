/**
 * SQLite 영속 큐 스토어. 같은 설치의 Hook 프로세스·MCP 프로세스가 **한 DB 파일을 공유**하므로
 * WAL 모드 + 트랜잭션으로 다중 writer 를 견딘다. 상태 전이만 담당하고, "어떤 오류가 재시도냐"
 * 같은 정책 판단은 sender(drain) 쪽이 내린다.
 *
 * 설계 불변식:
 *  - **조용한 폐기 금지**: 7일 재시도 소진·영구오류·상한 초과 모두 폐기 대신 NEEDS_ATTENTION/수집중단으로 남긴다.
 *  - **의존 순서**: claim 은 선행 의존이 전부 SENT(또는 24h 뒤 정리돼 행이 없음)인 항목만 집는다.
 *  - **lease**: INFLIGHT 는 lease_until 까지 다른 sender 가 못 집는다(중복 전송 방지). 크래시로
 *    lease 가 만료되면 다시 PENDING 처럼 claim 가능(재전송은 같은 식별자라 서버가 흡수).
 */
import Database from 'better-sqlite3';
import { createHash } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import type { NewQueueItem, QueueItem, QueueOp, QueueStatus } from './types.js';
import { QueueFullError } from './types.js';

/** 큐 저장 상한(256 MiB). 미전송 backlog 합이 이를 넘으면 수집 중단. */
export const DEFAULT_CAP_BYTES = 256 * 1024 * 1024;
/** 전송 완료 payload 로컬 보존(24h). 이후 payload 를 비워 디스크를 되돌린다(행은 의존 참조로 잠시 유지). */
export const SENT_PAYLOAD_TTL_MS = 24 * 60 * 60 * 1000;
/** 자동 재시도 창(7일). 이후 NEEDS_ATTENTION. */
export const RETRY_WINDOW_MS = 7 * 24 * 60 * 60 * 1000;

interface Row {
  id: number;
  connection_id: string;
  op: string;
  capture_uuid: string | null;
  project_binding_id: number | null;
  idempotency_key: string | null;
  payload: string | null;
  payload_hash: string;
  depends_on: string;
  status: string;
  attempts: number;
  next_attempt_at: number;
  last_error: string | null;
  enqueued_at: number;
  sent_at: number | null;
  size_bytes: number;
}

export interface QueueStoreOptions {
  capBytes?: number;
  retryWindowMs?: number;
  sentPayloadTtlMs?: number;
}

export class QueueStore {
  private readonly db: Database.Database;
  private readonly capBytes: number;
  private readonly retryWindowMs: number;
  private readonly sentPayloadTtlMs: number;

  constructor(path: string, opts: QueueStoreOptions = {}) {
    if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true });
    this.db = new Database(path);
    this.db.pragma('journal_mode = WAL');
    this.db.pragma('busy_timeout = 5000');
    this.db.pragma('synchronous = NORMAL');
    this.capBytes = opts.capBytes ?? DEFAULT_CAP_BYTES;
    this.retryWindowMs = opts.retryWindowMs ?? RETRY_WINDOW_MS;
    this.sentPayloadTtlMs = opts.sentPayloadTtlMs ?? SENT_PAYLOAD_TTL_MS;
    this.migrate();
  }

  private migrate(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS queue_item (
        id                 INTEGER PRIMARY KEY AUTOINCREMENT,
        connection_id      TEXT    NOT NULL,
        op                 TEXT    NOT NULL,
        capture_uuid       TEXT,
        project_binding_id INTEGER,
        idempotency_key    TEXT,
        payload            TEXT,
        payload_hash       TEXT    NOT NULL,
        depends_on         TEXT    NOT NULL DEFAULT '[]',
        status             TEXT    NOT NULL DEFAULT 'PENDING',
        attempts           INTEGER NOT NULL DEFAULT 0,
        next_attempt_at    INTEGER NOT NULL DEFAULT 0,
        last_error         TEXT,
        enqueued_at        INTEGER NOT NULL,
        sent_at            INTEGER,
        size_bytes         INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS ix_queue_claim  ON queue_item (status, next_attempt_at);
      CREATE INDEX IF NOT EXISTS ix_queue_sent   ON queue_item (status, sent_at);
    `);
  }

  /** 미전송 backlog 바이트 합(상한 비교·status 표시용). SENT 는 곧 정리되므로 세지 않는다. */
  backlogBytes(): number {
    const row = this.db
      .prepare(`SELECT COALESCE(SUM(size_bytes),0) AS n FROM queue_item WHERE status != 'SENT'`)
      .get() as { n: number };
    return row.n;
  }

  /**
   * 새 연산을 큐에 넣는다. 미전송 backlog + 신규 크기가 상한을 넘으면 QueueFullError(수집 중단 신호).
   * payloadHash 로 같은 내용 재삽입을 흡수(재시도 중 원본이 바뀌어도 다른 내용이 같은 ID 로 가지 않음).
   */
  enqueue(item: NewQueueItem, now: number = Date.now()): QueueItem {
    const payloadJson = JSON.stringify(item.payload ?? null);
    const sizeBytes = Buffer.byteLength(payloadJson, 'utf8');
    const backlog = this.backlogBytes();
    if (backlog + sizeBytes > this.capBytes) throw new QueueFullError(backlog, this.capBytes);

    const hash = createHash('sha256').update(payloadJson).digest('hex');
    const dependsOn = item.dependsOn ?? [];
    const info = this.db
      .prepare(
        `INSERT INTO queue_item
           (connection_id, op, capture_uuid, project_binding_id, idempotency_key,
            payload, payload_hash, depends_on, status, attempts, next_attempt_at, enqueued_at, size_bytes)
         VALUES (@connectionId, @op, @captureUuid, @projectBindingId, @idempotencyKey,
            @payload, @payloadHash, @dependsOn, 'PENDING', 0, @now, @now, @sizeBytes)`,
      )
      .run({
        connectionId: item.connectionId,
        op: item.op,
        captureUuid: item.captureUuid ?? null,
        projectBindingId: item.projectBindingId ?? null,
        idempotencyKey: item.idempotencyKey ?? null,
        payload: payloadJson,
        payloadHash: hash,
        dependsOn: JSON.stringify(dependsOn),
        now,
        sizeBytes,
      });
    return this.get(Number(info.lastInsertRowid))!;
  }

  get(id: number): QueueItem | null {
    const row = this.db.prepare(`SELECT * FROM queue_item WHERE id = ?`).get(id) as Row | undefined;
    return row ? this.toItem(row) : null;
  }

  /**
   * 보낼 수 있는 다음 항목 하나를 집어 lease 를 건다(INFLIGHT). 조건:
   *  - status PENDING, next_attempt_at ≤ now
   *  - 선행 의존이 모두 SENT 이거나 이미 정리돼 행이 없음(= 전에 SENT 였다는 뜻)
   *  - lease 미보유 또는 만료
   * 가장 오래 기다린 것부터(enqueued_at, id). 트랜잭션으로 집어 중복 claim 을 막는다.
   */
  claim(leaseMs: number, now: number = Date.now()): QueueItem | null {
    const tx = this.db.transaction((): QueueItem | null => {
      const candidate = this.db
        .prepare(
          `SELECT * FROM queue_item
             WHERE status = 'PENDING' AND next_attempt_at <= @now
             ORDER BY enqueued_at ASC, id ASC`,
        )
        .all({ now }) as Row[];
      for (const row of candidate) {
        if (!this.dependenciesSatisfied(row)) continue;
        this.db
          .prepare(
            `UPDATE queue_item SET status='INFLIGHT', next_attempt_at=@leaseUntil WHERE id=@id`,
          )
          .run({ id: row.id, leaseUntil: now + leaseMs });
        // INFLIGHT 는 next_attempt_at 를 lease 만료로 재활용 → 만료되면 자동으로 다시 claim 가능.
        return this.get(row.id)!;
      }
      return null;
    });
    return tx();
  }

  /** 선행 의존이 모두 SENT(또는 정리돼 사라짐)인가. 하나라도 미전송이면 아직 못 보낸다. */
  private dependenciesSatisfied(row: Row): boolean {
    const deps = JSON.parse(row.depends_on) as number[];
    if (deps.length === 0) return true;
    for (const depId of deps) {
      const dep = this.db.prepare(`SELECT status FROM queue_item WHERE id = ?`).get(depId) as
        | { status: string }
        | undefined;
      if (dep && dep.status !== 'SENT') return false; // 미전송(BLOCKED/NEEDS_ATTENTION 포함) → 대기
    }
    return true;
  }

  /** 전송 성공(ACK). payload 는 24h 뒤 purge 로 비운다. */
  markSent(id: number, now: number = Date.now()): void {
    this.db
      .prepare(`UPDATE queue_item SET status='SENT', sent_at=@now, last_error=NULL WHERE id=@id`)
      .run({ id, now });
  }

  /**
   * 일시 실패 → 재시도 예약. 자동 재시도 창(7일)을 넘겼으면 NEEDS_ATTENTION 로 올린다.
   * nextAttemptAt 은 sender 가 계산한 backoff 시각(epoch ms).
   */
  markRetry(id: number, nextAttemptAt: number, error: string, now: number = Date.now()): QueueStatus {
    const row = this.db.prepare(`SELECT enqueued_at, attempts FROM queue_item WHERE id = ?`).get(id) as
      | { enqueued_at: number; attempts: number }
      | undefined;
    if (!row) return 'NEEDS_ATTENTION';
    if (now - row.enqueued_at > this.retryWindowMs) {
      this.db
        .prepare(
          `UPDATE queue_item SET status='NEEDS_ATTENTION', attempts=attempts+1, last_error=@error WHERE id=@id`,
        )
        .run({ id, error });
      this.blockDependents(id);
      return 'NEEDS_ATTENTION';
    }
    this.db
      .prepare(
        `UPDATE queue_item SET status='PENDING', attempts=attempts+1, next_attempt_at=@next, last_error=@error WHERE id=@id`,
      )
      .run({ id, next: nextAttemptAt, error });
    return 'PENDING';
  }

  /** 영구 실패(409/413/미지원 schema 등). 폐기하지 않고 NEEDS_ATTENTION + 후행 BLOCKED. */
  markPermanentFailure(id: number, error: string): void {
    this.db
      .prepare(`UPDATE queue_item SET status='NEEDS_ATTENTION', last_error=@error WHERE id=@id`)
      .run({ id, error });
    this.blockDependents(id);
  }

  /** 선행 실패가 확정되면 그 항목에 의존하는 미전송 후행을 BLOCKED 로(특히 complete 선전송 금지). */
  blockDependents(failedId: number): void {
    const open = this.db
      .prepare(`SELECT id, depends_on FROM queue_item WHERE status IN ('PENDING','INFLIGHT')`)
      .all() as { id: number; depends_on: string }[];
    for (const row of open) {
      const deps = JSON.parse(row.depends_on) as number[];
      if (deps.includes(failedId)) {
        this.db.prepare(`UPDATE queue_item SET status='BLOCKED' WHERE id=@id`).run({ id: row.id });
        this.blockDependents(row.id); // 연쇄
      }
    }
  }

  /** 응답 유실로 lease 만 만료된(INFLIGHT, next_attempt_at<now) 항목을 PENDING 으로 되돌린다(크래시 복구). */
  reclaimExpiredLeases(now: number = Date.now()): number {
    const info = this.db
      .prepare(`UPDATE queue_item SET status='PENDING' WHERE status='INFLIGHT' AND next_attempt_at <= @now`)
      .run({ now });
    return info.changes;
  }

  /** SENT 항목의 payload 를 24h 뒤 비운다(디스크 회수). 행은 depends_on 참조가 끊길 때까지 가볍게 유지. */
  purgeSentPayloads(now: number = Date.now()): number {
    const cutoff = now - this.sentPayloadTtlMs;
    const info = this.db
      .prepare(
        `UPDATE queue_item SET payload=NULL, size_bytes=0
           WHERE status='SENT' AND sent_at IS NOT NULL AND sent_at < @cutoff AND payload IS NOT NULL`,
      )
      .run({ cutoff });
    return info.changes;
  }

  /** status 요약(doctor/status CLI 용). */
  counts(): Record<QueueStatus, number> {
    const rows = this.db.prepare(`SELECT status, COUNT(*) AS n FROM queue_item GROUP BY status`).all() as {
      status: QueueStatus;
      n: number;
    }[];
    const out: Record<QueueStatus, number> = {
      PENDING: 0,
      INFLIGHT: 0,
      SENT: 0,
      BLOCKED: 0,
      NEEDS_ATTENTION: 0,
    };
    for (const r of rows) out[r.status] = r.n;
    return out;
  }

  /** 사람 개입이 필요한 항목(NEEDS_ATTENTION) — status CLI 가 나열. */
  needsAttention(): QueueItem[] {
    const rows = this.db
      .prepare(`SELECT * FROM queue_item WHERE status='NEEDS_ATTENTION' ORDER BY enqueued_at ASC`)
      .all() as Row[];
    return rows.map((r) => this.toItem(r));
  }

  close(): void {
    this.db.close();
  }

  private toItem(row: Row): QueueItem {
    return {
      id: row.id,
      connectionId: row.connection_id,
      op: row.op as QueueOp,
      captureUuid: row.capture_uuid,
      projectBindingId: row.project_binding_id,
      idempotencyKey: row.idempotency_key,
      payload: row.payload == null ? null : JSON.parse(row.payload),
      payloadHash: row.payload_hash,
      dependsOn: JSON.parse(row.depends_on) as number[],
      status: row.status as QueueStatus,
      attempts: row.attempts,
      nextAttemptAt: row.next_attempt_at,
      lastError: row.last_error,
      enqueuedAt: row.enqueued_at,
      sentAt: row.sent_at,
      sizeBytes: row.size_bytes,
    };
  }
}
