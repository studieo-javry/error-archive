Schedule-Manager
---

### [common]
- [ ] @RestControllerAdvice 도입 여부
- [ ] 에러 응답 및 예외 처리 구조 고민
- [x] `swagger` API 문서화
- [ ] k8s 인프라 환경 구축 `cluster` `namespace` ···
- [ ] 내부 svc <-> svc 간 통신 방식 고민 (현재 동기 HTTP + internal JWT 인증/인가) -> gRPC? HTTP? event?
- [ ] spring security가 제공하는 인가 아키텍처 구조 확인 후 도입 여부 체크 `진행중`
- [ ] mTLS + service mesh 도입 (k8s 도입 이후)

### [gateway]
- [x] `8000` 포트 기반 엣지 서버 구축
- [x] 라우팅 정책 설정 `core-api` `iam-api`
- [ ] 내부망에 대한 네트워크 격리 정책 설정 `8080` `8081` ···
- [ ] Retry 의 함정 — POST 재시도에 대해 멱등성 처리 필수
- [ ] `application.yml` -- stg / prod 추가


### [iam-api]
- [x] GitHub OAuth 기반 소셜 로그인/로그아웃 시스템 + 로그인 유도 후 복귀 기능 추가
- [x] JWT 기반 access/refresh 토큰 발급/만료/재발급 시스템
- [x] social 마이페이지 조회/수정 시스템 -> `auth` `user` 도메인 위치 고민 중
- [x] follow/unfollow 시스템 (팔로우, 언팔로우, 팔로워 상태, 팔로워 수, 팔로잉 수)
- [x] workspace 생성/조회/수정/관리(멤버 조회, 권한 변경, 멤버 추방, 본인 탈퇴)/삭제 시스템 `진행중`
- [x] workspace invitation by email/link (초대 생성, pending 리스트, 초대 만료, 프리뷰, 초대 수락) -> 현재 env 주입 방식 구현됨 (로컬: mailHog) `진행중`
- [x] 회원 탈퇴 시스템

### [core-api]
- [ ] error-case 등록 시스템 (진행중)
- [x] attachments / code-snippet 등록/삭제/GC 배치 시스템
- [x] error-case 조회/수정/삭제 시스템
- [x] solution 등록/조회/수정/삭제 시스템
- [ ] template 등록/조회/수정/삭제 시스템
- [x] 내 케이스 목록 / 워크스페이스별 / 상태별 조회 시스템
- [ ] 케이스 상태 전이 방식 고민 (사용자 선택 vs 자동화)
- [ ] stacktrace 자동 파싱 방식을 가져갈지 말지
- [x] 에러 상세 페이지의 댓글 시스템

이번에 의도적으로 범위 밖으로 둔 것 (후속)

- 목록 조회 GET /error-cases?workspaceId=/my (페이징/필터) — "조회"의 detail은 됐고, list는 별도 기능
- 수정 시 스니펫·첨부 재연결, 워크스페이스 이동, occurredAt 변경
- 워크스페이스 ADMIN 모더레이션(타인 케이스 수정/삭제) — 현재 소유자 전용
후속 후보: 키워드 검색/기간/작성자 필터, status 인덱스


### [publish-api]


### [insight-api]
- [ ] `kafka-cluster` 구축


### [search-api]
- [ ] `elastic-search` 검색 엔진 도입 여부 결정
