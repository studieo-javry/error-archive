package org.studieojavry.coreapi.demo.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseRepositoryPort
import org.studieojavry.coreapi.errorcase.case.application.port.ErrorCaseSearchCriteria
import org.studieojavry.coreapi.errorcase.case.domain.model.ErrorCase
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.ErrorCaseStatus
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Meta
import org.studieojavry.coreapi.errorcase.case.domain.model.vo.Visibility
import java.time.LocalDateTime

/**
 * 데모 워크스페이스 시드 콘텐츠 — **데모 봇 소유**의 그럴듯한 에러 케이스 다수.
 * 둘러보기가 "텅 비지 않고 살아있어 보이도록" 상태(OPEN/IN_PROGRESS/RESOLVED/CLOSED)·태그·발생일을 다양화한다.
 * 가시성은 WORKSPACE(데모 WS 내부 전용) — 전역 PUBLIC 피드로 새지 않는다.
 * 리셋 때 wipe 후 재생성되므로 항상 동일한 베이스라인을 보장한다.
 */
@Service
class DemoSeeder(
    private val errorCaseRepository: ErrorCaseRepositoryPort,
) {

    /** 시드 세트 크기 — 부트스트랩이 "시드가 바뀌었는지"(재시드 필요) 판정에 사용. */
    val expectedCount: Int get() = SEED.size

    /** 데모 WS 에 케이스가 하나도 없을 때만 시드(기동 시 populate 용). 생성 건수 반환. */
    @Transactional
    fun ensureSeeded(workspaceId: Long, botUserId: Long): Int {
        val existing = errorCaseRepository.search(criteria(workspaceId, limit = 1))
        if (existing.isNotEmpty()) return 0
        return seedFresh(workspaceId, botUserId)
    }

    /** 무조건 시드 세트를 생성(리셋의 wipe 직후 호출). 생성 건수 반환. */
    @Transactional
    fun seedFresh(workspaceId: Long, botUserId: Long): Int {
        SEED.forEach { s ->
            val case = ErrorCase.create(
                ownerUserId = botUserId,
                title = s.title,
                project = s.project,
                snapshot = null,
                description = s.description,
                meta = Meta.create(workspaceId = workspaceId, severityCode = null),
                visibility = Visibility.WORKSPACE,
                snippets = emptyList(),
                attachments = emptyList(),
                tags = s.tags,
                occurredAt = LocalDateTime.now().minusDays(s.daysAgo).minusHours(s.hoursAgo),
            )
            // 상태 다양화 — OPEN 에서 목표 상태까지 유효 전이. actor = 봇.
            when (s.status) {
                ErrorCaseStatus.OPEN, ErrorCaseStatus.DRAFT -> {}
                ErrorCaseStatus.IN_PROGRESS -> case.transitionTo(ErrorCaseStatus.IN_PROGRESS, botUserId)
                ErrorCaseStatus.RESOLVED -> {
                    case.transitionTo(ErrorCaseStatus.IN_PROGRESS, botUserId)
                    case.transitionTo(ErrorCaseStatus.RESOLVED, botUserId)
                }
                ErrorCaseStatus.CLOSED -> case.transitionTo(ErrorCaseStatus.CLOSED, botUserId)
            }
            errorCaseRepository.save(case)
        }
        return SEED.size
    }

    private fun criteria(workspaceId: Long, limit: Int) = ErrorCaseSearchCriteria(
        workspaceId = workspaceId,
        ownerUserId = null,
        status = null,
        fingerprint = null,
        visibility = null,
        cursorCreatedAt = null,
        cursorId = null,
        limit = limit,
    )

    private data class Seed(
        val title: String,
        val project: String,
        val description: String,
        val tags: List<String>,
        val status: ErrorCaseStatus = ErrorCaseStatus.OPEN,
        val daysAgo: Long = 0,
        val hoursAgo: Long = 0,
    )

    companion object {
        private val SEED = listOf(
            Seed("로그인 후 프로필 로딩 시 NullPointerException", "core-api",
                "OAuth 로그인 직후 홈 대시보드 진입에서 간헐적으로 NPE. 아바타 URL 이 null 인 계정의 프로필 하이드레이션 중 터지는 것으로 추정. 신규 가입자에서 재현율이 높다.",
                listOf("kotlin", "npe", "oauth"), ErrorCaseStatus.RESOLVED, 1, 3),
            Seed("PDF 공개 export 중 OutOfMemoryError", "publish-api",
                "이미지가 많은 공개 아티클을 PDF 로 뽑을 때 1 CPU 인스턴스가 힙을 소진해 OOM. 동시 요청이 겹치면 더 빨리 터진다. 렌더 큐/페이지네이션 검토 중.",
                listOf("jvm", "oom", "pdf"), ErrorCaseStatus.IN_PROGRESS, 0, 5),
            Seed("알림 SSE 연결이 30초마다 끊김", "noti-api",
                "EventSource 가 약 30초 간격으로 재연결. 게이트웨이 idle timeout 과 heartbeat 주기 불일치 의심. keep-alive 프레임 주기를 timeout 보다 짧게 조정하면 해결될 듯.",
                listOf("sse", "timeout", "realtime"), ErrorCaseStatus.OPEN, 0, 1),
            Seed("댓글 멘션 자동완성이 대소문자에 민감", "core-api",
                "@멘션 입력 시 핸들을 대문자로 치면 후보가 안 뜬다. 저장은 소문자 정규화인데 검색 쿼리가 case-sensitive 로 나가는 것으로 보임. ignore-case 매칭으로 통일 필요.",
                listOf("search", "ux", "mention"), ErrorCaseStatus.RESOLVED, 3, 0),
            Seed("DLQ 재처리 배치가 간헐적으로 정체", "insight-api",
                "이벤트 소비 실패분이 DLQ 로 가는데 재처리 배치가 특정 배치 크기에서 멈춰 적체. 배치 크기/인터벌을 설정값으로 빼서 운영 중 튜닝 가능하게 바꾼 뒤 관찰 중.",
                listOf("kafka", "dlq", "batch"), ErrorCaseStatus.IN_PROGRESS, 2, 4),
            Seed("워크스페이스 전환 후 권한 캐시가 60초간 stale", "core-api",
                "멤버 역할을 WRITE→READ 로 낮춰도 최대 60초 동안 이전 권한으로 통과. role 캐시 TTL(60s) trade-off. 즉시 반영이 필요하면 역할 변경 시 캐시 evict 추가.",
                listOf("cache", "authz", "redis"), ErrorCaseStatus.OPEN, 5, 2),
            Seed("이미지 업로드 시 OCI S3 403 (aws-chunked)", "core-api",
                "AWS SDK v2 putObject 가 aws-chunked 로 보내는데 OCI 가 미지원 → 403 'required information to complete authentication'. S3Configuration 에 chunkedEncodingEnabled(false) 로 해결.",
                listOf("s3", "oci", "upload"), ErrorCaseStatus.RESOLVED, 7, 0),
            Seed("초대 메일 발송 535 Envelope From not authorized", "iam-api",
                "OCI Email Delivery 로 초대 메일 발송 시 535. noreply 발신 주소가 콘솔 Approved Senders 에 미등록. 코드 문제가 아니라 콘솔 설정 이슈.",
                listOf("email", "smtp", "oci"), ErrorCaseStatus.CLOSED, 9, 6),
            Seed("gateway 크로스노드 pod to pod 호출이 503", "gateway",
                "platform 노드의 gateway 가 apps 노드 서비스로 라우팅할 때 503. k3s 내장 netpol 이 크로스노드 FORWARD 를 REJECT + bare-name DNS NXDOMAIN 복합. .apps FQDN + iptables ACCEPT 로 우회.",
                listOf("k8s", "network", "dns"), ErrorCaseStatus.RESOLVED, 10, 1),
            Seed("로그인 브라우저 요청이 403 Invalid CORS request", "insight-api",
                "로그인 후 특정 API 가 403 Invalid CORS. 다운스트림이 prod 경로에 CORS 를 와일드카드로 등록한 버그. CORS 는 gateway 소유 — 다운스트림은 local 전용으로만 등록하도록 수정.",
                listOf("cors", "security", "gateway"), ErrorCaseStatus.RESOLVED, 11, 3),
            Seed("추천 팔로우 조회 p95 7초 — fallback full-scan", "core-api",
                "suggested-followees 가 cold-start 에서 전체 스캔 fallback 을 타 p95 가 7초대. fan-out 병렬화 + full-scan 제거로 89ms 까지 단축.",
                listOf("performance", "query", "n+1"), ErrorCaseStatus.RESOLVED, 12, 2),
            Seed("recent-active-cases unread 계산이 앱 메모리 적재", "core-api",
                "홈 위젯의 unread 계산을 앱이 전 건 적재 후 집계 → 케이스 많으면 느리고 메모리 압박. DB 집계 쿼리로 전환.",
                listOf("performance", "dashboard"), ErrorCaseStatus.RESOLVED, 13, 0),
            Seed("발행물 공개 페이지 첫 로드 느림(렌더 캐시 미스)", "publish-api",
                "공개 페이지 첫 요청이 전체 엔티티 로드 + HTML 렌더라 느림. Caffeine 렌더 캐시 추가, HIT 시 엔티티 로드까지 스킵하도록 개선.",
                listOf("cache", "performance", "publish"), ErrorCaseStatus.IN_PROGRESS, 4, 7),
            Seed("무중단 배포 중 순단 발생(Recreate 전략)", "infra",
                "배포가 Recreate 라 구 파드 종료~새 파드 Ready 사이 순단. RollingUpdate(maxUnavailable 0) + readiness 게이트로 0초 전환.",
                listOf("k8s", "deploy", "rolling"), ErrorCaseStatus.RESOLVED, 15, 0),
            Seed("Flyway 마이그레이션이 부팅 시 미실행", "iam-api",
                "Spring Boot 4.0 에서 flyway-core 만으론 마이그레이션 미동작. spring-boot-flyway autoconfig 모듈을 함께 추가해야 부팅 시 migrate.",
                listOf("flyway", "boot4", "db"), ErrorCaseStatus.RESOLVED, 16, 4),
            Seed("환불 동시 요청 시 예약 초과(RR 스냅샷)", "core-api",
                "잠금 전 비잠금 read 가 RR 스냅샷에 고정돼 in-flight 합산 가드가 샘. 예약을 잠긴 행의 컬럼으로 이동해 해결(DDL 필요).",
                listOf("concurrency", "mysql", "isolation"), ErrorCaseStatus.OPEN, 6, 8),
            Seed("쿠폰 발급 동시성 — 멀티 인스턴스 중복 발급", "core-api",
                "2개 인스턴스가 공유 DB 에서 쿠폰 한도를 초과 발급. Redisson 분산 락 + Redis 장애 시 조건부 UPDATE+UNIQUE fallback 로 이중화.",
                listOf("concurrency", "redis", "lock"), ErrorCaseStatus.IN_PROGRESS, 8, 1),
            Seed("SSE 알림이 다른 노드 접속 사용자에게 미도달", "noti-api",
                "SseEmitter 레지스트리가 노드-로컬이라 A노드 생성 이벤트가 B노드 접속자에게 안 감. Redis pub/sub(noti:realtime) fan-out 으로 모든 노드 구독.",
                listOf("sse", "redis", "pubsub"), ErrorCaseStatus.RESOLVED, 2, 10),
            Seed("per-IP rate limit 이 인스턴스 수만큼 느슨", "gateway",
                "토큰 버킷이 프로세스 메모리라 N대면 실질 한도가 N배. Redis Lua 원자 토큰버킷으로 전환해 전 인스턴스가 한 버킷 공유.",
                listOf("ratelimit", "redis", "scaling"), ErrorCaseStatus.RESOLVED, 1, 6),
            Seed("스텝 본문 마크다운 펜스가 리터럴로 노출", "publish-api",
                "발행 아티클에서 코드블록 펜스가 렌더되지 않고 리터럴 텍스트로 보임. 렌더러의 펜스 파싱 수정.",
                listOf("markdown", "render", "bug"), ErrorCaseStatus.CLOSED, 18, 0),
            Seed("내부 JWT 서명 비용으로 호출 지연", "iam-api",
                "서비스 간 호출마다 RS256 내부 토큰을 새로 서명해 CPU/지연 증가. (subject, aud, roles) 키로 서명 결과 캐싱해 분할상환.",
                listOf("jwt", "performance", "internal-auth"), ErrorCaseStatus.RESOLVED, 20, 3),
            Seed("startup probe 예산 부족으로 롤링 실패", "infra",
                "콜드 스타트가 길어 startup probe 가 먼저 죽어 파드가 CrashLoop. failureThreshold/period 를 늘려 기동 예산 확보.",
                listOf("k8s", "probe", "deploy"), ErrorCaseStatus.CLOSED, 22, 5),
            Seed("클라이언트 4xx 가 전부 500 으로 매핑", "core-api",
                "검증 실패/권한 오류가 ProblemDetail 로 안 내려가고 500 으로 뭉개짐. shared-error GlobalExceptionHandler 로 4xx/5xx 일관 매핑.",
                listOf("error-handling", "problemdetail"), ErrorCaseStatus.RESOLVED, 24, 2),
            Seed("팔로우 목록 isFollowing 플래그 누락", "iam-api",
                "팔로워/팔로잉 목록 응답에 viewer 기준 isFollowing 이 빠져 FE 가 상태 표시를 못 함. 배치 프로필 조회에 viewerUserId 반영.",
                listOf("social", "api", "bug"), ErrorCaseStatus.OPEN, 3, 9),
        )
    }
}
