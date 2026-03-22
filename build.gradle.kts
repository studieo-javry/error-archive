// Composite Build 루트 — 각 모듈은 includeBuild로 독립 프로젝트이므로
// 여기서는 전체 빌드를 오케스트레이션하는 태스크만 정의합니다.

tasks.register("buildAll") {
	description = "Builds all included builds"
	dependsOn(
		gradle.includedBuild("core-api").task(":build"),
		gradle.includedBuild("iam-api").task(":build"),
		gradle.includedBuild("publish-api").task(":build"),
		gradle.includedBuild("insight-api").task(":build"),
		gradle.includedBuild("noti-api").task(":build"),
	)
}

tasks.register("testAll") {
	description = "Runs tests for all included builds"
	dependsOn(
		gradle.includedBuild("core-api").task(":test"),
		gradle.includedBuild("iam-api").task(":test"),
		gradle.includedBuild("publish-api").task(":test"),
		gradle.includedBuild("insight-api").task(":test"),
		gradle.includedBuild("noti-api").task(":test"),
	)
}

tasks.register("cleanAll") {
	description = "Cleans all included builds"
	dependsOn(
		gradle.includedBuild("core-api").task(":clean"),
		gradle.includedBuild("iam-api").task(":clean"),
		gradle.includedBuild("publish-api").task(":clean"),
		gradle.includedBuild("insight-api").task(":clean"),
		gradle.includedBuild("noti-api").task(":clean"),
	)
}
