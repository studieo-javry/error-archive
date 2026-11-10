/**
 * 로컬 큐 DB 위치. 설정 토큰(비밀)과 달리 큐는 상태 데이터라 XDG 상태 디렉터리에 둔다.
 * 우선순위: $ERROR_ARCHIVE_QUEUE > $XDG_STATE_HOME/error-archive-agent/queue.db >
 *          ~/.local/state/error-archive-agent/queue.db
 * 같은 설치의 Hook/MCP 프로세스가 같은 경로를 공유해 하나의 큐를 본다.
 */
import { homedir } from 'node:os';
import { join } from 'node:path';

export function resolveQueuePath(env: NodeJS.ProcessEnv = process.env): string {
  if (env.ERROR_ARCHIVE_QUEUE) return env.ERROR_ARCHIVE_QUEUE;
  const stateHome = env.XDG_STATE_HOME && env.XDG_STATE_HOME.trim() !== ''
    ? env.XDG_STATE_HOME
    : join(homedir(), '.local', 'state');
  return join(stateHome, 'error-archive-agent', 'queue.db');
}
