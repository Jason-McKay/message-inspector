# syntax=docker/dockerfile:1

# ---- build stage -------------------------------------------------------------
# Pinned to the same Java 21 / Maven 3.9 line the wrapper uses (.mvn/wrapper).
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so this layer is cached unless pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Then the sources. Tests are skipped here: the suite needs Docker (Testcontainers)
# and is the CI job's responsibility, not the image build's.
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user rather than the image default.
RUN groupadd --system app && useradd --system --gid app app
USER app

COPY --from=build /build/target/message-inspector-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
