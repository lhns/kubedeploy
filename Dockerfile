FROM eclipse-temurin:25

COPY server/target/scala-*/*.sh.bat ./

CMD exec ./*.sh.bat
