// 로컬 오프라인 이벤트 큐(P1-3) — JSONL 추가기록. 네이티브 의존 없음, 오프라인 durable.
//
//  - Hook 프로세스(수명 짧음)는 **append 만**(빠름, 네트워크 X). sender(MCP 서버)가 배치 전송.
//  - clientEventId 로 재전송 멱등. remove() 는 mkdir 락으로 append-during-rewrite 유실 방지.
//  - 계정 네임스페이스(baseUrl+token 해시)별 파일 → 토큰 교체 시 섞이지 않음.

import { createHash } from 'node:crypto';
import { appendFileSync, existsSync, mkdirSync, readFileSync, renameSync, rmdirSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import type { EventItemRequest } from './types.js';

export function defaultQueueDir(env: NodeJS.ProcessEnv = process.env): string {
  return env.ERROR_ARCHIVE_QUEUE_DIR ?? join(homedir(), '.config', 'error-archive-agent', 'queue');
}

/** 계정 격리용 네임스페이스(네트워크 없이 토큰 기준). */
export function accountNamespace(baseUrl: string, token: string): string {
  return createHash('sha256').update(`${baseUrl}|${token}`).digest('hex').slice(0, 16);
}

export class EventQueue {
  private readonly file: string;
  private readonly rejectedFile: string;
  private readonly seqFile: string;
  private readonly lockDir: string;

  constructor(namespace: string, opts: { dir?: string } = {}) {
    const dir = opts.dir ?? defaultQueueDir();
    this.file = join(dir, `${namespace}.jsonl`);
    this.rejectedFile = join(dir, `${namespace}.rejected.jsonl`);
    this.seqFile = join(dir, `${namespace}.seq`);
    this.lockDir = join(dir, `${namespace}.lock`);
  }

  /** 세션/스트림과 무관한 로컬 단조 수열(큐 전역). */
  nextLocalSequence(): number {
    mkdirSync(dirname(this.seqFile), { recursive: true });
    let n = 0;
    try {
      n = parseInt(readFileSync(this.seqFile, 'utf8').trim(), 10) || 0;
    } catch {
      n = 0;
    }
    n += 1;
    writeFileSync(this.seqFile, String(n));
    return n;
  }

  enqueue(event: EventItemRequest): void {
    mkdirSync(dirname(this.file), { recursive: true });
    appendFileSync(this.file, `${JSON.stringify(event)}\n`);
  }

  /** pending 전체(파싱 불가 라인은 건너뜀). */
  readAll(): EventItemRequest[] {
    if (!existsSync(this.file)) return [];
    const out: EventItemRequest[] = [];
    for (const line of readFileSync(this.file, 'utf8').split('\n')) {
      if (!line.trim()) continue;
      try {
        out.push(JSON.parse(line) as EventItemRequest);
      } catch {
        /* 손상 라인 무시 */
      }
    }
    return out;
  }

  size(): number {
    return this.readAll().length;
  }

  /** 처리 완료 id 제거 + rejected 는 dead-letter 로 이동. 락 하에 원자 재작성(동시 append 유실 방지). */
  remove(doneIds: Set<string>, rejected: EventItemRequest[] = []): void {
    this.withLock(() => {
      const remaining = this.readAll().filter((e) => !doneIds.has(e.clientEventId));
      if (rejected.length > 0) {
        appendFileSync(this.rejectedFile, `${rejected.map((r) => JSON.stringify(r)).join('\n')}\n`);
      }
      const tmp = `${this.file}.tmp`;
      writeFileSync(tmp, remaining.length ? `${remaining.map((e) => JSON.stringify(e)).join('\n')}\n` : '');
      renameSync(tmp, this.file);
    });
  }

  private withLock(fn: () => void): void {
    mkdirSync(dirname(this.lockDir), { recursive: true });
    const deadline = Date.now() + 3000;
    for (;;) {
      try {
        mkdirSync(this.lockDir); // 원자적 — 이미 있으면 throw
        break;
      } catch {
        if (Date.now() > deadline) break; // 락 획득 실패 시에도 진행(최악 드문 경합)
      }
    }
    try {
      fn();
    } finally {
      try {
        rmdirSync(this.lockDir);
      } catch {
        /* ignore */
      }
    }
  }
}
