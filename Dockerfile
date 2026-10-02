# MF-Stock: build with JDK 21, run on JRE 21
# Compatible with: Render (PORT=10000), Koyeb (PORT auto), HuggingFace Spaces (7860)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src src
RUN javac -encoding UTF-8 -d classes src/*.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/classes classes
COPY web web
EXPOSE 7860 10000
CMD ["sh", "-c", "java -Xms128m -Xmx384m -cp classes StockServer ${PORT:-7860}"]
