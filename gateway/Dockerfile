# gateway — API 엣지. Spring Boot 임베디드 Tomcat 이 8443 에서 TLS 종료(HTTPS).
# 빌드 전: ./gradlew :gateway:bootJar 후 bootJar(plain 제외)를 이 폴더에 app.jar 로 복사.
# arm64 노드용: docker buildx build --platform linux/arm64 -t gateway:<tag> -o type=docker,dest=gateway-<tag>.tar .
# TLS keystore(/certs/keystore.p12)는 이미지에 넣지 않고 런타임에 Secret(gateway-tls) 볼륨으로 마운트.
FROM eclipse-temurin:25-jre
ENV TZ=Asia/Seoul
RUN useradd -r -u 1001 app
WORKDIR /app
COPY app.jar /app/app.jar
USER app
EXPOSE 8443
# 컨테이너 메모리 limit 존중 + 힙 여유 확보(1vCPU/소용량 노드).
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
