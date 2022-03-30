ARG DOCKER_PROXY=""
FROM ${DOCKER_PROXY}openjdk:17

COPY server/target/scala-*/*.sh.bat ./

CMD exec ./*.sh.bat
