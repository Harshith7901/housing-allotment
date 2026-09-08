# Multi-stage so the shipped image carries a JRE and a jar, not a JDK and a Maven cache.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies are resolved from the POM alone first, so that editing source does not
# invalidate the dependency layer on every rebuild.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# Runs unprivileged. A service that writes an audit log has no business being root.
RUN useradd --system --create-home --shell /usr/sbin/nologin housing
COPY --from=build /build/target/housing-allotment-1.0.0.jar app.jar
COPY verify ./verify
USER housing

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
