# Repo-root context wrapper for edelivery (standard-service-delivery@8.0.0
# shares one build context across builds[]).
FROM eclipse-temurin:25-alpine AS build

WORKDIR /app
COPY code/ ./

ARG PROJECT=edelivery
RUN --mount=type=cache,target=/root/.gradle,id=efti-gate-ee-gradle-cache,sharing=locked \
    ./gradlew $PROJECT:build --info

FROM eclipse-temurin:25-jre-alpine
RUN adduser -S user

WORKDIR /app
# The AS4 keystore is never baked in: mount the per-environment Secret at /app/certs/own.p12
# (KEYSTORE_DIR, KEYSTORE_PASSWORD). Without it edelivery fails to start.
# See docs/specs/deploy/resource-and-secrets-requirements.md §3.5.
RUN rm -fr /usr/sbin /bin/ch*

ARG VERSION=dev
ENV VERSION=$VERSION
ENV LOGGER_SERVICE_VERSION=$VERSION

ARG PROJECT=edelivery
COPY --from=build /app/$PROJECT/build/libs ./

USER user

ENV TZ=Europe/Tallinn
ENV JAVA_MAIN_OPTIONS="-Xss256K -XX:MaxRAMPercentage=80 -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError --add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED"
CMD java $JAVA_MAIN_OPTIONS -jar *.jar

ARG PORT=8081
ENV PORT=$PORT
EXPOSE $PORT
