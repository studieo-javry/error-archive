import assert from 'node:assert/strict';
import { test } from 'node:test';
import { singleFlight } from '../src/singleFlight.js';

test('진행 중이면 같은 Promise 를 공유(task 1회만 시작)', async () => {
  let calls = 0;
  let resolve!: (v: number) => void;
  const sf = singleFlight(() => {
    calls++;
    return new Promise<number>((r) => {
      resolve = r;
    });
  });
  const p1 = sf();
  const p2 = sf();
  assert.equal(p1, p2, '겹친 호출은 같은 Promise');
  assert.equal(calls, 1, 'task 는 1회만');
  resolve(7);
  assert.equal(await p1, 7);
  assert.equal(await p2, 7);
});

test('완료 후 다시 호출하면 새 flight 시작', async () => {
  let calls = 0;
  const sf = singleFlight(async () => {
    calls++;
    return calls;
  });
  assert.equal(await sf(), 1);
  assert.equal(await sf(), 2); // 이전 flight 종료 → 새로 시작
  assert.equal(calls, 2);
});
