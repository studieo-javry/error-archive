// 동일 프로세스 single-flight — 이미 진행 중인 작업이 있으면 새로 시작하지 않고 그 Promise 를 공유한다.
// 주기 flush(10초)와 조회직전 flush 가 겹쳐 같은 로컬 큐를 동시에 재작성하는 경합을 막는다.
// (별도 프로세스·수동 flush CLI 간 경합은 eventQueue 의 파일 락이 담당.)
export function singleFlight<T>(task: () => Promise<T>): () => Promise<T> {
  let inFlight: Promise<T> | null = null;
  return (): Promise<T> => {
    if (inFlight) return inFlight;
    inFlight = (async () => {
      try {
        return await task();
      } finally {
        inFlight = null;
      }
    })();
    return inFlight;
  };
}
