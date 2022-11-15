FROM openjdk:17

COPY server/target/scala-*/*.sh.bat ./

CMD exec ./*.sh.bat
