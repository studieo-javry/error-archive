-- shedlock 시각 컬럼을 TIMESTAMPTZ → TIMESTAMP(UTC 벽시계) 로. ShedLock 공식 Postgres 스키마와 동일.
-- usingDbTime() 은 timezone('utc', CURRENT_TIMESTAMP)(= tz 없는 UTC 시각)를 넣는데, 컬럼이 TIMESTAMPTZ 면
-- JDBC 세션 TZ(JVM TZ=Asia/Seoul)로 해석돼 9시간 이르게 저장됐다(12:10 실행 → 03:10). 락 판단은 같은 규칙이라 정상, 표시만 틀림.
-- 기존 값은 그 KST 해석을 되돌려(AT TIME ZONE 'Asia/Seoul') 원래 UTC 시각으로 — 배포 중 잡힌 락도 그대로 유지.
ALTER TABLE shedlock
    ALTER COLUMN lock_until TYPE TIMESTAMP USING lock_until AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN locked_at  TYPE TIMESTAMP USING locked_at  AT TIME ZONE 'Asia/Seoul';
