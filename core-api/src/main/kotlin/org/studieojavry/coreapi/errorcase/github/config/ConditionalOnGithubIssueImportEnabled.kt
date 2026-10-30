package org.studieojavry.coreapi.errorcase.github.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * "GitHub Issue 로 Case 만들기"(미리보기·생성) — `core.github.enabled=true` **그리고** `core.github.issue-import.enabled=true` 일 때만.
 * 기본 꺼짐: Issue 본문을 Case 로 바로 옮기는 흐름이 구조화된 해결 기록(Step·Solution)과 맞는지 정해질 때까지 API 를 열지 않는다.
 * Issue 를 근거로 **연결**하고 닫는 PR 을 소급으로 붙이는 기능(P1-b 소급 부착)은 이 플래그와 무관하게 동작한다.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(prefix = "core.github", name = ["enabled", "issue-import.enabled"], havingValue = "true")
annotation class ConditionalOnGithubIssueImportEnabled
