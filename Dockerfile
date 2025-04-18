ARG PROJECT="look"

FROM gradle:jdk23-alpine AS jlink

WORKDIR /tmp

COPY . .

ARG PROJECT
RUN --mount=type=secret,id=GITHUB_ACTOR \
    --mount=type=secret,id=GITHUB_TOKEN \
    export GITHUB_ACTOR=$(cat /run/secrets/GITHUB_ACTOR); \
    export GITHUB_TOKEN=$(cat /run/secrets/GITHUB_TOKEN); \
    gradle -PjavaVersion=23 --console=plain --quiet --no-daemon --exclude-task=test :${PROJECT}:jlink -PnoVersionTag=true


FROM alpine:3

ARG UID=6148
RUN adduser \
 --system \
 --disabled-password \
 --gecos "" \
 --home "/nonexistent" \
 --shell "/sbin/nologin" \
 --no-create-home \
 --uid "${UID}" \
  glam
USER glam

WORKDIR /look

ARG PROJECT
COPY --from=jlink /tmp/${PROJECT}/build/${PROJECT} /look

ENTRYPOINT [ "./bin/java" ]
