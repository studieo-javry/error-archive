# noti-api — Spring Boot fat jar 런타임 이미지.
# 빌드 전: ./gradlew :noti-api:bootJar 후 bootJar(plain 제외)를 이 폴더에 app.jar 로 복사.
# arm64 노드용: docker buildx build --platform linux/arm64 -t noti-api:<tag> -o type=docker,dest=noti-api-<tag>.tar .
FROM eclipse-temurin:25-jre
ENV TZ=Asia/Seoul
RUN useradd -r -u 1001 app
WORKDIR /app
COPY app.jar /app/app.jar
USER app
EXPOSE 8082
# 컨테이너 메모리 limit 존중 + 힙 여유 확보(1vCPU/소용량 노드).
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
