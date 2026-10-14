FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B test package
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd -r xopy && useradd -r -g xopy xopy && mkdir -p /app/uploads && chown -R xopy:xopy /app
COPY --from=build /build/target/xopy-backend-0.1.0.jar /app/app.jar
USER xopy
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
