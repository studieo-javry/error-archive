# publish-api — Spring Boot fat jar 런타임 이미지. (PDF 렌더용 한글 폰트는 jar 리소스에 번들됨)
# 빌드 전: ./gradlew :publish-api:bootJar 후 bootJar(plain 제외)를 이 폴더에 app.jar 로 복사.
# arm64 노드용: docker buildx build --platform linux/arm64 -t publish-api:<tag> -o type=docker,dest=publish-api-<tag>.tar .
# ※ 배포 전제: publish-api 는 아직 .env.prod 미작성 → Secret/ConfigMap 준비 후 배포.
FROM eclipse-temurin:25-jre
ENV TZ=Asia/Seoul
RUN useradd -r -u 1001 app
WORKDIR /app
COPY app.jar /app/app.jar
USER app
EXPOSE 8084
# 컨테이너 메모리 limit 존중 + 힙 여유 확보(1vCPU/소용량 노드). PDF 렌더는 일시적 힙 사용 큼.
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
