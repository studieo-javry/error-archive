# 공개 페이지 렌더 캐시 설계 (publish-api)

> 목적: `/p/{slug}` 공개 아티클의 **HTML 렌더 CPU 부하 오프로드**. 부하테스트에서 렌더가 병목임을 확인.

## 왜

- 부하테스트(실 stg, k6) 결과: `/p/{slug}` 단일 파드는 **~160 RPS에서 CPU 포화**(31KB HTML 렌더가 CPU 무거움).
- 앞단 캐시 실측: 렌더를 캐시하면 **처리량 ~7.6배(160→1,218 RPS), p95 640ms→80ms, publish-api CPU ~650m→1m**.
- 게이트웨이 필터 캐시는 불가(SCG Server WebMVC 5.0.1 에 `LocalResponseCache` 없음) + 게이트웨이는 별 네임스페이스.
  → **앱 레벨 캐시**가 가장 정확·격리적: 조회수 보존 + 무효화 정확 + 코드 한 곳.

## 무엇을 캐시하나

- **렌더 결과 HTML 문자열만** 캐시. 컨트롤러의 조회수/쿠키 로직은 캐시 밖 → **view_count 정확 유지**.
- 라이브러리: Spring Cache + Caffeine(로컬 인메모리).

## 정확성 — 캐시 key = `slug@updatedAt`

핵심은 무효화. 발행물 편집 경로별 `version`/`updatedAt` 동작을 확인해 key 를 정했다:

| 이벤트 | version | updatedAt | 렌더 영향 |
|---|---|---|---|
| 재발행(스냅샷 변경) | +1 | 갱신 | 있음 |
| 재발행/메타편집(제목·요약·visibility·options 만) | **유지** | 갱신 | **있음**(제목·options 는 렌더에 반영) |
| 조회수/다운로드 증가 | 유지 | **미변경**(타깃 UPDATE) | 없음 |

- `version` 은 메타편집 시 안 올라가므로 **key 로 부적합**(제목/options 바뀌어도 stale).
- `updatedAt` 은 **모든 편집에서 갱신**되고 **조회수 증가엔 안 바뀜** → 캐시 key 로 정확.
- 언퍼블리시/삭제: 컨트롤러가 매 요청 `lookup(slug)`(DB)로 게이팅 → 없으면 404, 캐시 안 탐. **캐시가 stale 을 서빙하지 않음.**

즉 매 요청: `lookup`(가벼운 DB 조회) + 조회수 기록(쿠키 dedup) + **렌더(캐시)**. 비싼 렌더만 캐시.

## 안전성

- 웹 렌더(`forPdf=false`)는 첨부를 **안정 라우트**로 처리하고 `imageEmbedUrl`(presign)을 안 씀 → 결과 HTML 이 `p` 에 대한 **순수함수**. 같은 `updatedAt` 이면 항상 동일 HTML → 캐시 안전.
- Set-Cookie 유출 문제 없음(응답을 컨트롤러가 정상 생성, 쿠키는 캐시 밖).

## 구성

- `PublicPageCacheConfig` — `@EnableCaching` + CaffeineCacheManager(`publicPage`), `maximumSize=500`(엔트리 ~31KB → 최대 ~15MB), `expireAfterAccess=1h`(메모리 가드).
- `PublicPageRenderCache.render(slug, stamp, p)` — `@Cacheable(key = slug + '@' + stamp)`, stamp=`updatedAt`.
- `PublicPageController.viewPage` — 조회수/쿠키 로직 유지, 렌더만 `renderCache.render(slug, p.updatedAt.toString(), p)` 로 교체.

## 트레이드오프 / 향후

- 캐시는 **파드별 로컬**(레플리카 늘면 중복). 엔트리 작아 무해. 필요 시 분산 캐시(Redis)로 승격 가능.
- 더 강한 오프로드가 필요하면(초당 수천) 이 위에 edge/CDN(nginx proxy_cache)을 얹을 수 있음(둘은 배타적 아님).
- PDF(`/p/{slug}.pdf`)는 캐시 대상 아님(다운로드·저빈도, 게이트웨이서 강한 rate-limit).

## 검증

- `./gradlew :publish-api:compileKotlin` + `:publish-api:test` 통과.
- 앞단 nginx 캐시 실측치가 이 접근의 상한(렌더 제거 시 ~7.6x)을 뒷받침.
