/**
 * 근거 수집 정책(환경변수). 코드 토큰 설정과 분리해 둔다.
 *  - ERROR_ARCHIVE_EVIDENCE_ROOTS  수집 허용 루트(`:` 구분). 생략 시 현재 작업 디렉터리.
 *  - ERROR_ARCHIVE_SCREENSHOTS     '1'/'true' 면 스크린샷(이미지) 수집 허용(기본 OFF).
 */
import { delimiter } from 'node:path';

export interface EvidencePolicy {
  roots: string[];
  screenshotsEnabled: boolean;
}

export function loadEvidencePolicy(env: NodeJS.ProcessEnv = process.env): EvidencePolicy {
  const raw = env.ERROR_ARCHIVE_EVIDENCE_ROOTS;
  const roots = raw
    ? raw.split(delimiter).map((s) => s.trim()).filter((s) => s.length > 0)
    : [process.cwd()];
  const flag = (env.ERROR_ARCHIVE_SCREENSHOTS ?? '').trim().toLowerCase();
  return { roots, screenshotsEnabled: flag === '1' || flag === 'true' };
}
