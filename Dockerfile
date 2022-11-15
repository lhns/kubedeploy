FROM openjdk:18

COPY server/target/scala-*/*.sh.bat ./

CMD exec ./*.sh.bat
