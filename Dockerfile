# 테스트용 이미지. 운영 서버에서는 target/s3-viewer.jar 만 복사해 `java -jar` 로 실행하면 된다.
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

FROM eclipse-temurin:8-jre
COPY --from=build /src/target/s3-viewer.jar /app/s3-viewer.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/s3-viewer.jar"]
