FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y pipx \
    && rm -rf /var/lib/apt/lists/*

RUN pipx install --include-deps pipx \
    && /root/.local/bin/pipx install --global --include-deps apprise

WORKDIR /app
# Passed in by CI (--build-arg KOMF_VERSION=1.8.0). Matches the jar produced by
# ./gradlew -PkomfVersion=<version> :komf-app:shadowJar
ARG KOMF_VERSION
COPY komf-app/build/libs/komf-${KOMF_VERSION}.jar ./komf.jar
ENV LC_ALL=en_US.UTF-8
ENV KOMF_CONFIG_DIR="/config"
# Generational ZGC + 8-byte object headers (JEP 519, production in JDK 25).
# Overriding JAVA_TOOL_OPTIONS in compose replaces this whole value, so include
# these flags yourself if you set it.
ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:+UseCompactObjectHeaders"
ENTRYPOINT ["java","-jar", "/app/komf.jar"]
EXPOSE 8085

LABEL org.opencontainers.image.url=https://github.com/loocool2/komf org.opencontainers.image.source=https://github.com/loocool2/komf
