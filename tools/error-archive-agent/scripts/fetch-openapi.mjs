// 실행 중인 백엔드에서 `agent` 그룹 OpenAPI 스펙을 받아 openapi/agent-openapi.json 으로 저장한다.
// 커넥터(TS) 계약 타입(src/generated/agent-api.ts)의 소스 스냅샷을 갱신하는 수동 단계.
//
// Kotlin 쪽 에이전트 DTO/엔드포인트를 바꾼 뒤 실행하는 흐름:
//   1) 백엔드 기동 (예: tools/error-archive-agent/e2e/run-local-e2e.sh KEEP_UP=1)
//   2) npm run openapi:snapshot   # 이 스크립트 — 스냅샷 갱신
//   3) npm run openapi:gen        # 스냅샷 → TS 타입 재생성
//   4) 변경된 openapi/agent-openapi.json + src/generated/agent-api.ts 커밋
//
// 소스 우선순위: OPENAPI_URL(env) → 게이트웨이(:8000/api-docs/agent) → core 직접(:8081/v3/api-docs/agent).
import { writeFileSync } from 'node:fs';

const candidates = process.env.OPENAPI_URL
  ? [process.env.OPENAPI_URL]
  : ['http://127.0.0.1:8000/api-docs/agent', 'http://127.0.0.1:8081/v3/api-docs/agent'];

let spec = null;
for (const url of candidates) {
  try {
    const res = await fetch(url);
    if (res.ok) {
      spec = await res.json();
      console.error(`[openapi] fetched from ${url}`);
      break;
    }
    console.error(`[openapi] ${url} → ${res.status}`);
  } catch (e) {
    console.error(`[openapi] ${url} → ${String(e)}`);
  }
}

if (!spec) {
  console.error('[openapi] 스펙을 가져오지 못했습니다. 백엔드가 떠 있어야 합니다:\n  ' + candidates.join('\n  '));
  process.exit(1);
}

const out = new URL('../openapi/agent-openapi.json', import.meta.url);
writeFileSync(out, JSON.stringify(spec, null, 2) + '\n');
console.error('[openapi] openapi/agent-openapi.json 갱신 완료');
