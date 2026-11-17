// 로컬 오프라인 이벤트 큐 — **이벤트별 독립 파일**(maildir 식). 공유 파일을 재작성하지 않으므로
// enqueue·remove 간 경합·유실이 **구조적으로 없다**(하나를 저장/삭제하려고 다른 이벤트나 공용 상태를 바꾸지 않음).
//
//  - enqueue: pending/<shard>/<clientEventId>.json 에 tmp+rename(원자). 다른 파일/공용 상태 무간섭 → 락 불필요.
//  - remove : 완료 id 파일 삭제, rejected 는 rejected/ 로 이동. 개별 파일이라 서로 무간섭·멱등.
//  - 샤딩  : clientEventId(UUID) 앞 2자리로 256 논리 샤드 → 한 디렉토리의 파일 수 분산.
//  - 순서  : observedAt + clientEventId 정렬. **전역 .seq 카운터를 두지 않는다**(공용 가변 상태=경합 재발원).
//  - 백프레셔: 여유 디스크가 minFreeBytes 미만이면 enqueue 를 건너뛴다(디스크 보호). soft 상한 초과는 경고만.
//  - 레거시 <namespace>.jsonl 은 sender 가 migrateLegacy() 로 이관(멱등, 전량 성공 후 삭제; 구버전 Hook 재기록 대비 재호출 안전).

import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, statfsSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { basename, dirname, join } from 'node:path';
import type { QueuedEvent } from './types.js';

export function defaultQueueDir(env: NodeJS.ProcessEnv = process.env): string {
  return env.ERROR_ARCHIVE_QUEUE_DIR ?? join(homedir(), '.config', 'error-archive-agent', 'queue');
}

/** 계정 격리용 네임스페이스(네트워크 없이 토큰 기준). */
export function accountNamespace(baseUrl: string, token: string): string {
  return createHash('sha256').update(`${baseUrl}|${token}`).digest('hex').slice(0, 16);
}

const DEFAULT_MIN_FREE_BYTES = 50 * 1024 * 1024; // 50 MiB 미만이면 enqueue 백프레셔(디스크 보호)
const DEFAULT_SOFT_MAX_FILES = 10_000; // pending 이 이보다 많으면 경고(관측용, 차단 아님)

/** clientEventId(UUID) 앞 2자리 → 256 논리 샤드. 안전 문자만 사용. */
function shardOf(clientEventId: string): string {
  const safe = clientEventId.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
  return (safe.slice(0, 2) || '00').padEnd(2, '0');
}

export interface EventQueueOptions {
  dir?: string;
  /** 이 바이트 미만 여유 디스크면 enqueue 백프레셔(기본 50 MiB). */
  minFreeBytes?: number;
  /** pending 이 이 수를 넘으면 경고(기본 10000). */
  softMaxFiles?: number;
}

export class EventQueue {
  private readonly pendingDir: string;
  private readonly rejectedDir: string;
  private readonly legacyFile: string; // 구버전 공유 JSONL(<namespace>.jsonl) — 이관 대상
  private readonly minFreeBytes: number;
  private readonly softMaxFiles: number;

  constructor(namespace: string, opts: EventQueueOptions = {}) {
    const dir = opts.dir ?? defaultQueueDir();
    const root = join(dir, namespace);
    this.pendingDir = join(root, 'pending');
    this.rejectedDir = join(root, 'rejected');
    this.legacyFile = join(dir, `${namespace}.jsonl`);
    this.minFreeBytes = opts.minFreeBytes ?? DEFAULT_MIN_FREE_BYTES;
    this.softMaxFiles = opts.softMaxFiles ?? DEFAULT_SOFT_MAX_FILES;
  }

  /** 저장 성공(또는 이미 존재)이면 true, 디스크 백프레셔로 건너뛰면 false. 호출측(마이그레이션)이 실제 저장 여부를 안다. */
  enqueue(event: QueuedEvent): boolean {
    const shardDir = join(this.pendingDir, shardOf(event.clientEventId));
    const finalPath = join(shardDir, `${event.clientEventId}.json`);
    if (existsSync(finalPath)) return true; // 이미 적재됨(멱등) — 재작성·디스크 체크 불필요
    mkdirSync(shardDir, { recursive: true });
    if (this.lowOnDisk(shardDir)) {
      process.stderr.write('[events] 디스크 여유 부족 — 이벤트 적재 건너뜀(백프레셔)\n');
      return false;
    }
    const tmp = `${finalPath}.${randomUUID()}.tmp`;
    writeFileSync(tmp, JSON.stringify(event));
    renameSync(tmp, finalPath); // 원자적 — 미완성 .tmp 는 .json 으로 보이지 않음
    return true;
  }

  /** pending 전체(파싱 불가/동시삭제는 스킵). observedAt → clientEventId 순 정렬. */
  readAll(): QueuedEvent[] {
    const out: QueuedEvent[] = [];
    for (const file of this.listJsonFiles(this.pendingDir)) {
      try {
        out.push(JSON.parse(readFileSync(file, 'utf8')) as QueuedEvent);
      } catch {
        /* ENOENT(동시 삭제) · 파싱 실패 → 스킵 */
      }
    }
    out.sort((a, b) => {
      const t = (a.observedAt ?? '').localeCompare(b.observedAt ?? '');
      return t !== 0 ? t : a.clientEventId.localeCompare(b.clientEventId);
    });
    if (out.length > this.softMaxFiles) {
      process.stderr.write(`[events] pending ${out.length} 건(soft 상한 ${this.softMaxFiles}) — 전송 적체 가능\n`);
    }
    return out;
  }

  size(): number {
    return this.listJsonFiles(this.pendingDir).length;
  }

  /** 완료 id 파일 삭제 + rejected 는 rejected/ 로 이동(dead-letter). 모두 개별 파일 → 무간섭·멱등. */
  remove(doneIds: Set<string>, rejected: QueuedEvent[] = []): void {
    for (const id of doneIds) {
      rmSync(this.pendingPath(id), { force: true }); // 없어도 OK(멱등)
    }
    for (const ev of rejected) {
      const dstDir = join(this.rejectedDir, shardOf(ev.clientEventId));
      mkdirSync(dstDir, { recursive: true });
      const dst = join(dstDir, `${ev.clientEventId}.json`);
      try {
        renameSync(this.pendingPath(ev.clientEventId), dst); // pending → rejected 이동
      } catch {
        writeFileSync(dst, JSON.stringify(ev)); // 원본이 이미 없으면 전달된 내용으로 기록
      }
    }
  }

  /**
   * 레거시 공유 JSONL(<namespace>.jsonl)을 이벤트별 파일로 이관. **멱등**(같은 clientEventId → 같은 파일 경로).
   *  - 유실 방지(구버전 Hook 과의 경합): 레거시를 **원자적 rename 으로 claim** 한 뒤 처리한다. claim 이후의
   *    append 는 구버전 Hook 이 새 <namespace>.jsonl 에 쓰게 되고, 다음 호출이 그걸 처리하므로 유실되지 않는다.
   *  - 유실 방지(디스크 부족): enqueue 가 실제 저장 여부(boolean)를 반환하므로, 하나라도 미저장이면 조각을
   *    삭제하지 않고 유지한다(디스크 복구 후 재시도). 미완료 조각(.migrating.*)은 다음 호출 때 먼저 재처리한다.
   */
  migrateLegacy(): void {
    // (1) 이전에 claim 됐으나 미완료된 조각(.migrating.*) 먼저 재처리(디스크 복구/재기동 후).
    for (const leftover of this.listMigratingFiles()) this.drainLegacyFile(leftover);
    // (2) 현재 레거시를 원자적으로 claim 후 처리. claim 뒤 append 는 새 레거시로 가 다음 호출이 처리 → 무유실.
    if (!existsSync(this.legacyFile)) return;
    const claimed = `${this.legacyFile}.migrating.${randomUUID()}`;
    try {
      renameSync(this.legacyFile, claimed);
    } catch {
      return; // 경쟁적으로 사라졌으면 할 일 없음
    }
    this.drainLegacyFile(claimed);
  }

  /** claim 된 조각을 이벤트별 파일로 이관. 전량 저장 성공(또는 이미 존재) 시에만 조각 삭제(미저장분 유지). */
  private drainLegacyFile(path: string): void {
    let content: string;
    try {
      content = readFileSync(path, 'utf8');
    } catch {
      return;
    }
    let allMigrated = true;
    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      let ev: QueuedEvent;
      try {
        ev = JSON.parse(line) as QueuedEvent;
      } catch {
        continue; // 손상 라인은 건너뜀(이관 대상 아님)
      }
      // enqueue 가 false(디스크 백프레셔로 미저장)거나 예외면 조각을 지우지 않는다 → 다음 호출에서 재시도.
      try {
        if (!this.enqueue(ev)) allMigrated = false;
      } catch {
        allMigrated = false;
      }
    }
    if (allMigrated) {
      try {
        rmSync(path, { force: true });
      } catch {
        /* ignore */
      }
    }
  }

  private listMigratingFiles(): string[] {
    const dir = dirname(this.legacyFile);
    const prefix = `${basename(this.legacyFile)}.migrating.`;
    try {
      return readdirSync(dir)
        .filter((f) => f.startsWith(prefix))
        .map((f) => join(dir, f));
    } catch {
      return [];
    }
  }

  private pendingPath(clientEventId: string): string {
    return join(this.pendingDir, shardOf(clientEventId), `${clientEventId}.json`);
  }

  private listJsonFiles(baseDir: string): string[] {
    const files: string[] = [];
    let shards: string[];
    try {
      shards = readdirSync(baseDir);
    } catch {
      return files; // 디렉토리 없음 → 빈 큐
    }
    for (const shard of shards) {
      let entries: string[];
      try {
        entries = readdirSync(join(baseDir, shard));
      } catch {
        continue; // 샤드가 파일이거나 사라짐 → 스킵
      }
      for (const e of entries) {
        if (e.endsWith('.json')) files.push(join(baseDir, shard, e)); // .tmp(미완성) 등 제외
      }
    }
    return files;
  }

  private lowOnDisk(existingPath: string): boolean {
    try {
      const st = statfsSync(existingPath);
      return Number(st.bavail) * Number(st.bsize) < this.minFreeBytes;
    } catch {
      return false; // statfs 불가 → 백프레셔 없이 진행
    }
  }
}
