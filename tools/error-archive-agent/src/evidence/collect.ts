/**
 * 근거 파일 수집(#145 §5). 경계 내부 파일만 받아 **로컬 전송영역으로 복사·해시**한 뒤 그 스냅샷을 올린다
 * — 재시도 중 원본이 바뀌어도 다른 내용이 같은 ID 로 가지 않게 한다. 텍스트는 전송 전 비밀정보를 제거하고,
 * 마스킹을 적용할 수 없으면 **전송 보류**(거절)한다. 스크린샷은 기본 OFF(프로젝트 opt-in). HAR 는 제외.
 */
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { basename, extname, join } from 'node:path';
import { maskString } from '../hooks/mask.js';
import { resolveWithinBoundary } from './boundary.js';

/** 근거 유형. 실행결과(로그·테스트·diff) / 스크린샷(이미지) / AI 생성(모델 산출물). */
export type EvidenceKind = 'EXECUTION_RESULT' | 'SCREENSHOT' | 'AI_GENERATED';

export class EvidenceError extends Error {
  constructor(
    readonly code: 'OUT_OF_BOUNDARY' | 'EXCLUDED_TYPE' | 'SCREENSHOTS_DISABLED' | 'TOO_LARGE' | 'MASK_FAILED',
    message: string,
  ) {
    super(message);
    this.name = 'EvidenceError';
  }
}

/** 파일당 상한(서버 첨부 정책과 동일 10 MiB). */
export const MAX_EVIDENCE_BYTES = 10 * 1024 * 1024;

const IMAGE_EXT = new Set(['.png', '.jpg', '.jpeg', '.webp']);
const TEXT_EXT = new Set(['.txt', '.log', '.diff', '.patch', '.json']);
const EXCLUDED_EXT = new Set(['.har']); // 민감정보 과다 — 수집 제외

export interface StageOptions {
  /** 수집 허용 루트(승인 프로젝트/산출물 디렉터리). 비면 cwd. */
  roots: string[];
  /** 스크린샷(이미지) 수집 허용 여부(기본 false). */
  screenshotsEnabled?: boolean;
  /** 명시 evidenceKind(AI 생성물 등). 생략 시 확장자로 추정. */
  kind?: EvidenceKind;
}

export interface StagedEvidence {
  /** 업로드에 쓸 스테이징 파일 경로(원본 basename 유지 → MIME/파일명 보존). */
  stagedPath: string;
  sha256: string;
  sizeBytes: number;
  evidenceKind: EvidenceKind;
  masked: boolean;
}

function classifyKind(ext: string, explicit?: EvidenceKind): EvidenceKind {
  if (explicit) return explicit;
  if (IMAGE_EXT.has(ext)) return 'SCREENSHOT';
  return 'EXECUTION_RESULT';
}

function sha256(buf: Buffer): string {
  return createHash('sha256').update(buf).digest('hex');
}

/**
 * 근거를 스테이징한다. 경계 밖·제외 유형·과대·스크린샷 비허용이면 EvidenceError.
 * 텍스트는 마스킹한 사본을, 이미지/기타는 원본 바이트를 복사한다. 반환된 stagedPath 를 업로드에 쓴다.
 */
export function stageEvidence(filePath: string, opts: StageOptions): StagedEvidence {
  const roots = opts.roots.length > 0 ? opts.roots : [process.cwd()];
  const real = resolveWithinBoundary(roots, filePath);
  if (!real) {
    throw new EvidenceError('OUT_OF_BOUNDARY', `근거가 허용 디렉터리 밖이거나 링크가 밖을 가리킵니다: ${filePath}`);
  }

  const ext = extname(real).toLowerCase();
  if (EXCLUDED_EXT.has(ext)) {
    throw new EvidenceError('EXCLUDED_TYPE', `이 유형은 수집하지 않습니다(${ext}).`);
  }

  const kind = classifyKind(ext, opts.kind);
  if (kind === 'SCREENSHOT' && !opts.screenshotsEnabled) {
    throw new EvidenceError('SCREENSHOTS_DISABLED', '스크린샷 수집이 꺼져 있습니다(프로젝트에서 opt-in 필요).');
  }

  const size = statSync(real).size;
  if (size > MAX_EVIDENCE_BYTES) {
    throw new EvidenceError('TOO_LARGE', `근거가 상한(${MAX_EVIDENCE_BYTES} bytes)을 넘습니다: ${size} bytes.`);
  }

  const stageDir = mkdtempSync(join(evidenceStagingRoot(), 'ea-ev-'));
  const stagedPath = join(stageDir, basename(real));

  let masked = false;
  if (TEXT_EXT.has(ext)) {
    // 텍스트 — 마스킹한 사본. 마스킹이 예외로 실패하면 전송 보류(원본 그대로 올리지 않음).
    let text: string;
    try {
      text = readFileSync(real, 'utf8');
    } catch (err) {
      throw new EvidenceError('MASK_FAILED', `텍스트 읽기 실패로 전송 보류: ${err instanceof Error ? err.message : String(err)}`);
    }
    const scrubbed = maskString(text);
    writeFileSync(stagedPath, scrubbed, 'utf8');
    masked = true;
    const buf = Buffer.from(scrubbed, 'utf8');
    return { stagedPath, sha256: sha256(buf), sizeBytes: buf.byteLength, evidenceKind: kind, masked };
  }

  // 이미지/기타 — 바이트 스냅샷 복사(마스킹 대상 아님).
  copyFileSync(real, stagedPath);
  const buf = readFileSync(stagedPath);
  return { stagedPath, sha256: sha256(buf), sizeBytes: buf.byteLength, evidenceKind: kind, masked };
}

/** 스테이징 루트(OS 임시 디렉터리 하위). 호출마다 mkdtemp 로 격리 디렉터리를 만든다. */
function evidenceStagingRoot(): string {
  const root = join(tmpdir(), 'error-archive-agent');
  mkdirSync(root, { recursive: true });
  return root;
}
