# ===== 构建阶段：Maven + JDK 21 =====
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先拷 pom 单独下依赖，利用 Docker 层缓存（源码改动不重拉依赖）
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ===== 运行阶段：仅 JRE =====
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /build/target/hotel-accounting-backend.jar app.jar

ENV TZ=Asia/Shanghai
EXPOSE 8081

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
