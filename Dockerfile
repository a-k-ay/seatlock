# ==== Stage 1: Build ====
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache Maven dependencies first (this layer only rebuilds when pom.xml changes)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Then copy source and build the fat jar
COPY src ./src
RUN mvn clean package -DskipTests -B

# ==== Stage 2: Runtime ====
FROM eclipse-temurin:21-jre-alpine

# Non-root user for security posture
RUN addgroup -S seatlock && adduser -S seatlock -G seatlock

WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
RUN chown seatlock:seatlock app.jar

USER seatlock

EXPOSE 8080

# Container-aware JVM: heap = 75% of container memory, force UTC
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=UTC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]