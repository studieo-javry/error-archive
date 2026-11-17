// 연결 안내(Phase 2) — SessionStart Hook 이 프로젝트 연결 상태에 따라 1회 안내를 만든다.
//
//  - 오프라인(네트워크 없음): 토큰-ns 동의 인덱스(Phase 1)만 읽어 승인/보류/거절을 판정.
//  - UNAPPROVED → "연결하세요" 유도. APPROVED → "연결 완료" 확인을 **최초 1회만**(connectAckAt 마킹 후 조용).
//  - 이 세션 보류(SNOOZED)·거절(DECLINED) 이면 무안내(null).
//  - systemMessage=사용자 표시(실측 확인: 터미널 표시됨), additionalContext=AI 문맥 주입.
//  - 안내는 승인 "다이얼로그"가 아니다 — 실제 승인은 connect_project(MCP) 또는 CLI 에서 받는다.

import {
  isCollectionApproved,
  isSnoozedForSession,
  markConnectAck,
  readConsent,
  type ConsentOptions,
} from './consentIndex.js';
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
  // 연결됨(APPROVED) — "연결 완료" 확인을 최초 1회만 띄우고 이후엔 조용(connectAckAt 마킹).
  if (isCollectionApproved(namespace, project.gitRoot, project.remote, opts)) {
    const rec = readConsent(namespace, project.gitRoot, opts);
    if (rec && rec.connectAckAt) return null; // 이미 확인 노출함 → 조용
    markConnectAck(namespace, project.gitRoot, opts); // 1회성 — 다음 세션부턴 null
    return {
      systemMessage: `✅ Error Archive: '${project.projectKey}' 연결 완료 · 오류 해결 과정 자동 기록 ON`,
      hookSpecificOutput: {
        hookEventName: 'SessionStart',
        additionalContext:
          `[Error Archive] 작업 프로젝트 '${project.projectKey}' 는 Error Archive 에 연결돼 있다. ` +
          '오류 해결 과정은 승인된 기록 정책에 따라 자동으로 Capture 로 남는다(사용자 검토 후 Case 반영).',
      },
    };
  }
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
