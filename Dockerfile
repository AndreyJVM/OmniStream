FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S omni && adduser -S -G omni omni
WORKDIR /app
COPY --from=build /build/target/omnistream-ai.jar app.jar
USER omni
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
