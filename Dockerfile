# MF-Stock: build with JDK 21, run on JRE 21
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src src
RUN javac -encoding UTF-8 -d classes src/*.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/classes classes
COPY web web
EXPOSE 10000
CMD ["java", "-Xms128m", "-Xmx512m", "-cp", "classes", "StockServer"]
