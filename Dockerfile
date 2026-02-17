FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/vk-leadbot-1.0.0.jar /app/bot.jar
ENV DB_PATH=/data/bot.db
VOLUME /data
CMD ["java", "-jar", "/app/bot.jar"]
