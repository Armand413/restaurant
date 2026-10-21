FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /source
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B verify

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 restaurant && useradd --uid 10001 --gid restaurant --create-home restaurant && mkdir -p /app/data/media && chown -R restaurant:restaurant /app
WORKDIR /app
COPY --from=build /source/target/restaurant-connect-1.0.0.jar app.jar
USER restaurant
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
