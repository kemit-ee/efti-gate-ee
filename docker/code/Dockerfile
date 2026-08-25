FROM eclipse-temurin:25-alpine AS build

WORKDIR /app
COPY . ./

ARG PROJECT
RUN --mount=type=cache,target=/root/.gradle,id=efti-gate-ee-gradle-cache \
    ./gradlew $PROJECT:build --info

FROM eclipse-temurin:25-jre-alpine
RUN adduser -S user

WORKDIR /app
COPY certs certs
RUN chmod a+r certs/own.p12
RUN rm -fr /usr/sbin /bin/ch*

ARG VERSION=dev
ENV VERSION=$VERSION
ENV LOGGER_SERVICE_VERSION=$VERSION

ARG PROJECT
COPY --from=build /app/$PROJECT/build/libs ./

USER user

ENV TZ=Europe/Tallinn
ENV JAVA_TOOL_OPTIONS="-Xss256K -XX:MaxRAMPercentage=80 -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError"
CMD java -jar *.jar

ARG PORT
ENV PORT=$PORT
EXPOSE $PORT
