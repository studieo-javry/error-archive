// 최초 연결 안내(Phase 2) — SessionStart Hook 이 미연결 프로젝트를 발견하면 1회 안내를 만든다.
//
//  - 오프라인(네트워크 없음): 토큰-ns 동의 인덱스(Phase 1)만 읽어 승인/보류/거절을 판정.
//  - 승인(APPROVED)·이 세션 보류(SNOOZED)·거절(DECLINED) 이면 무안내(null). 그 외(UNAPPROVED)만 안내.
//  - systemMessage=사용자 표시(실측 확인: 터미널 표시됨), additionalContext=AI 문맥 주입.
//  - 안내는 승인 "다이얼로그"가 아니다 — 실제 승인은 connect_project(MCP) 또는 CLI 에서 받는다.

import { isCollectionApproved, isSnoozedForSession, readConsent, type ConsentOptions } from './consentIndex.js';
import type { ResolvedProject } from './project.js';

export interface FirstConnectNotice {
  systemMessage: string;
  hookSpecificOutput: { hookEventName: 'SessionStart'; additionalContext: string };
}

/**
 * 미연결 프로젝트면 최초 연결 안내를 반환, 아니면 null. 네트워크 없음(토큰-ns 인덱스만 조회).
 * namespace = accountNamespace(baseUrl, token)(오프라인), sessionId = 현재 Claude Code 세션(SNOOZED 범위 판정).
 */
export function buildFirstConnectNotice(
  project: ResolvedProject,
  namespace: string,
  sessionId: string | null | undefined,
  opts: ConsentOptions = {},
): FirstConnectNotice | null {
  if (isCollectionApproved(namespace, project.gitRoot, project.remote, opts)) return null; // 이미 승인
  if (isSnoozedForSession(namespace, project.gitRoot, sessionId, opts)) return null; // 이 세션 보류("나중에")
  if (readConsent(namespace, project.gitRoot, opts)?.status === 'DECLINED') return null; // 거절(명시 재연결 전까지)
  return {
    systemMessage:
      `🔌 Error Archive: '${project.projectKey}' 프로젝트가 아직 연결되지 않았습니다. 오류 해결 과정을 자동 기록하려면 ` +
      '연결이 필요합니다 — connect_project 로 승인하거나 터미널에서 `npx -y @error-archive/agent connect-project`.',
    hookSpecificOutput: {
      hookEventName: 'SessionStart',
      additionalContext:
        `[Error Archive] 현재 작업 프로젝트 '${project.projectKey}' 는 아직 Error Archive 에 연결(승인)되지 않았다. ` +
        '승인 전에는 오류 해결 이벤트·자동 Capture 가 수집·전송되지 않는다. 사용자가 기록을 원하면 connect_project 도구로 ' +
        "연결을 제안해 승인받을 수 있고, '나중에' 면 connect_project(snooze=true), 원치 않으면 그대로 두면 된다.",
    },
  };
}
