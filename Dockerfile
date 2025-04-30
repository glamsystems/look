ARG PROJECT="look"

FROM gradle:jdk21-noble AS jlink

WORKDIR /tmp

COPY . .

ARG PROJECT
RUN --mount=type=secret,id=GITHUB_ACTOR \
    --mount=type=secret,id=GITHUB_TOKEN \
    export GITHUB_ACTOR=$(cat /run/secrets/GITHUB_ACTOR); \
    export GITHUB_TOKEN=$(cat /run/secrets/GITHUB_TOKEN); \
    gradle -PjavaVersion=24 --console=plain --no-daemon --exclude-task=test :${PROJECT}:jlink -PnoVersionTag=true

FROM ubuntu:noble

ARG UID=6148
RUN useradd \
 --no-log-init \
 --system \
 --home-dir "/nonexistent" \
 --no-create-home \
 --no-user-group \
 --shell "/sbin/nologin" \
 --uid "${UID}" \
 look

USER look

WORKDIR /look

ARG PROJECT
COPY --from=jlink /tmp/${PROJECT}/build/${PROJECT} /look

ENTRYPOINT [ "./bin/java" ]
