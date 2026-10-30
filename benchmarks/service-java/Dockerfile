FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/target/bench.jar ./bench.jar
USER nobody
# G1 named explicitly (the JVM picks Serial GC when it sees fewer than 2 CPUs or 1792 MiB) and a
# fixed heap, so the collector and heap do not depend on what the Docker VM happens to have.
ENTRYPOINT ["java", "-XX:+UseG1GC", "-Xms1g", "-Xmx1g", "-jar", "/app/bench.jar"]
