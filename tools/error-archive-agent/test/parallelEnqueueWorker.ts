// 실제 다중 프로세스 병렬 적재 테스트용 워커. argv: [dir, prefix, count]
import { EventQueue } from '../src/eventQueue.js';

const [, , dir, prefix, countStr] = process.argv;
const q = new EventQueue('ns', { dir });
const count = Number(countStr);
for (let i = 0; i < count; i++) {
  q.enqueue({
    clientEventId: `${prefix}-${i}`,
    eventType: 'PostToolUse',
    localSequence: 1,
    observedAt: '2026-11-10T00:00:00Z',
    redactedPayload: {},
    truncated: false,
    evidenceRefs: [],
  } as never);
}
