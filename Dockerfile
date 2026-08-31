# Stage 1: Build environment
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace
COPY pom.xml .
RUN mvn dependency:go-offline -B || true
COPY src ./src
RUN mvn clean package -DskipTests=true -B

# Stage 2: Hardened Runtime environment
FROM eclipse-temurin:21-jre-alpine

# Создание непривилегированного системного пользователя
RUN addgroup -S -g 10001 appgroup && \
    adduser -S -u 10001 -G appgroup -s /sbin/nologin appuser

WORKDIR /app

COPY --from=builder --chown=appuser:appgroup /workspace/target/*.jar /app/app.jar

# Защита прав на чтение/исполнение
RUN chmod 500 /app/app.jar

USER appuser:appgroup
EXPOSE 8080

# Флаги JVM для контейнеров и отключение удаленной отладки
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/app.jar"]
