import assert from 'node:assert/strict';
import { test } from 'node:test';
import { resolveQueuePath } from '../src/queue/path.js';

test('ERROR_ARCHIVE_QUEUE 가 최우선', () => {
  assert.equal(resolveQueuePath({ ERROR_ARCHIVE_QUEUE: '/tmp/x.db' }), '/tmp/x.db');
});

test('XDG_STATE_HOME 하위에 둔다', () => {
  assert.equal(resolveQueuePath({ XDG_STATE_HOME: '/state' }), '/state/error-archive-agent/queue.db');
});

test('기본은 ~/.local/state 하위', () => {
  const p = resolveQueuePath({});
  assert.match(p, /\.local\/state\/error-archive-agent\/queue\.db$/);
});
