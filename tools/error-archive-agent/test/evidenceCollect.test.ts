import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { EvidenceError, stageEvidence } from '../src/evidence/collect.js';

function root() {
  return mkdtempSync(join(tmpdir(), 'ea-col-'));
}

test('텍스트 근거: 마스킹한 사본을 스테이징(원본 비밀 비노출)', () => {
  const r = root();
  const f = join(r, 'app.log');
  writeFileSync(f, 'connecting with token eaa_abcdef12345678 done');
  const staged = stageEvidence(f, { roots: [r] });
  assert.equal(staged.evidenceKind, 'EXECUTION_RESULT');
  assert.equal(staged.masked, true);
  const content = readFileSync(staged.stagedPath, 'utf8');
  assert.ok(!content.includes('eaa_abcdef12345678'));
  assert.notEqual(staged.stagedPath, f); // 원본이 아닌 사본
});

test('경계 밖은 OUT_OF_BOUNDARY', () => {
  const r = root();
  const other = root();
  const f = join(other, 'x.log');
  writeFileSync(f, 'x');
  assert.throws(() => stageEvidence(f, { roots: [r] }), (e: unknown) => e instanceof EvidenceError && e.code === 'OUT_OF_BOUNDARY');
});

test('HAR 는 EXCLUDED_TYPE', () => {
  const r = root();
  const f = join(r, 'capture.har');
  writeFileSync(f, '{}');
  assert.throws(() => stageEvidence(f, { roots: [r] }), (e: unknown) => e instanceof EvidenceError && e.code === 'EXCLUDED_TYPE');
});

test('이미지(스크린샷)는 기본 OFF → SCREENSHOTS_DISABLED', () => {
  const r = root();
  const f = join(r, 'shot.png');
  writeFileSync(f, Buffer.from([0x89, 0x50, 0x4e, 0x47]));
  assert.throws(() => stageEvidence(f, { roots: [r] }), (e: unknown) => e instanceof EvidenceError && e.code === 'SCREENSHOTS_DISABLED');
});

test('이미지: opt-in 시 바이트 그대로 스테이징(SCREENSHOT)', () => {
  const r = root();
  const f = join(r, 'shot.png');
  const bytes = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a]);
  writeFileSync(f, bytes);
  const staged = stageEvidence(f, { roots: [r], screenshotsEnabled: true });
  assert.equal(staged.evidenceKind, 'SCREENSHOT');
  assert.equal(staged.masked, false);
  assert.deepEqual(readFileSync(staged.stagedPath), bytes); // 원본 바이트 보존
});

test('명시 kind=AI_GENERATED 우선', () => {
  const r = root();
  const f = join(r, 'notes.txt');
  writeFileSync(f, 'model notes');
  const staged = stageEvidence(f, { roots: [r], kind: 'AI_GENERATED' });
  assert.equal(staged.evidenceKind, 'AI_GENERATED');
});

test('스테이징 파일명은 원본 basename 유지(MIME/파일명 보존)', () => {
  const r = root();
  const f = join(r, 'result.json');
  writeFileSync(f, '{"ok":true}');
  const staged = stageEvidence(f, { roots: [r] });
  assert.ok(staged.stagedPath.endsWith('result.json'));
  assert.equal(staged.sizeBytes, Buffer.byteLength('{"ok":true}'));
});
