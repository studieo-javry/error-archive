// 연결 상태 조회(읽기 전용) — CLI(doctor) 와 MCP(connection_status) 가 공유하는 표현 로직.
// 네트워크 없이 토큰-ns 동의 인덱스만 해석한 ConnectionInspect 를 사람이 읽는 라벨로 변환한다.

import type { ConsentStatus } from './consentIndex.js';

export interface ConnectionInspect {
  /** 저장된 동의 레코드 상태(없으면 null). */
  recordStatus: ConsentStatus | null;
  /** 수집/귀속이 실제로 허용되는가(APPROVED + remote 일치). */
  connected: boolean;
  /** APPROVED 였으나 git remote 변경으로 무효화된 상태(재승인 필요). */
  remoteChanged: boolean;
  /** 레코드 갱신 시점(ISO) — 연결/상태 설정 시각. 없으면 null. */
  since: string | null;
}

export type ConnectionState = 'CONNECTED' | 'REMOTE_CHANGED' | 'SNOOZED' | 'DECLINED' | 'UNAPPROVED';

/** ConnectionInspect → 상태 enum + 사용자 표시 라벨(이모지 포함). 순수 함수. */
export function describeConnection(x: ConnectionInspect): { state: ConnectionState; label: string } {
  if (x.connected) return { state: 'CONNECTED', label: '✅ 연결됨' };
  if (x.remoteChanged) return { state: 'REMOTE_CHANGED', label: '⚠️ remote 변경 — 재연결 필요' };
  if (x.recordStatus === 'SNOOZED') return { state: 'SNOOZED', label: "⏸ 이 세션 보류('나중에')" };
  if (x.recordStatus === 'DECLINED') return { state: 'DECLINED', label: '🚫 거절됨(명시 재연결 필요)' };
  return { state: 'UNAPPROVED', label: '⚪ 미연결' };
}
