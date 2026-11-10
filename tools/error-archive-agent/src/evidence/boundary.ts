/**
 * 근거 파일 경계 검사(#145 §5). 승인된 루트(프로젝트/산출물 디렉터리) **내부**의 파일만 수집한다.
 * 핵심은 **심볼릭 링크 이탈 차단**: 경로를 realpath 로 정규화(링크를 끝까지 따라감)한 뒤 루트와 비교하므로,
 * 루트 안의 링크가 밖을 가리켜도 실제 대상이 루트 밖이면 거절된다.
 */
import { realpathSync } from 'node:fs';
import { resolve, sep } from 'node:path';

function canonical(path: string): string | null {
  try {
    return realpathSync(resolve(path));
  } catch {
    return null; // 존재하지 않거나 접근 불가 → 경계 밖으로 간주
  }
}

/**
 * candidate 가 roots 중 하나의 내부(또는 루트 자신)이면 그 정규 경로를, 아니면 null 을 돌려준다.
 * roots·candidate 모두 realpath 로 정규화해 비교(링크 이탈 차단). 존재하지 않는 파일은 null.
 */
export function resolveWithinBoundary(roots: string[], candidate: string): string | null {
  const real = canonical(candidate);
  if (!real) return null;
  for (const root of roots) {
    const rroot = canonical(root);
    if (!rroot) continue;
    if (real === rroot || real.startsWith(rroot + sep)) return real;
  }
  return null;
}
