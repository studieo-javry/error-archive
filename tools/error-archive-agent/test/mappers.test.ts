import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildAttemptDetail, buildCaptureContent, buildSolutionContent } from '../src/mappers.js';

test('buildCaptureContent: 빈 값 정리 + 재현/환경은 내용 있을 때만', () => {
  const c = buildCaptureContent({
    symptom: '  NPE  ',
    observedFacts: ['fact', '  ', ''],
    hypotheses: [],
    reproductionSteps: ['step1'],
    service: 'svc',
  });
  assert.equal(c.symptom, 'NPE');
  assert.deepEqual(c.observedFacts, ['fact']);
  assert.deepEqual(c.hypotheses, []);
  assert.deepEqual(c.reproduction, { steps: ['step1'], executed: false });
  assert.equal(c.environment?.service, 'svc');
});

test('buildCaptureContent: 재현 절차 없으면 reproduction 생략, 환경 비면 environment 생략', () => {
  const c = buildCaptureContent({ symptom: 'x' });
  assert.equal(c.reproduction, undefined);
  assert.equal(c.environment, undefined);
});

test('buildCaptureContent: reproductionExecuted 미지정이면 false(근거 없이 실행 단정 금지)', () => {
  const c = buildCaptureContent({ reproductionSteps: ['a', 'b'] });
  assert.equal(c.reproduction?.executed, false);
});

test('buildAttemptDetail: title 트림, 공백 필드는 undefined, evidenceRefs 정리', () => {
  const d = buildAttemptDetail({
    title: '  fix  ',
    hypothesis: '   ',
    action: 'patched',
    evidenceRefs: ['log1', ''],
  });
  assert.equal(d.title, 'fix');
  assert.equal(d.hypothesis, undefined);
  assert.equal(d.action, 'patched');
  assert.deepEqual(d.evidenceRefs, ['log1']);
});

test('buildSolutionContent: verification 있으면 executed 기본 false', () => {
  const s = buildSolutionContent({
    clientAttemptIds: ['a1', 'a1 '],
    verification: { result: 'PASSED', scope: 'LOCAL' },
  });
  assert.deepEqual(s.clientAttemptIds, ['a1', 'a1']);
  assert.equal(s.verification?.result, 'PASSED');
  assert.equal(s.verification?.executed, false);
});

test('buildSolutionContent: verification 없으면 undefined', () => {
  const s = buildSolutionContent({ clientAttemptIds: ['a1'] });
  assert.equal(s.verification, undefined);
});
