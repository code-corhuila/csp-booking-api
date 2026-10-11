FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# The poms first: the dependency layer is rebuilt only when a pom changes, not on every source edit.
COPY pom.xml .
COPY booking-core/pom.xml booking-core/
COPY booking-adapters/pom.xml booking-adapters/
COPY booking-app/pom.xml booking-app/
RUN mvn -q -B dependency:go-offline
COPY booking-core/src booking-core/src
COPY booking-adapters/src booking-adapters/src
COPY booking-app/src booking-app/src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /src/booking-app/target/booking-app-0.1.0.jar /app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app.jar"]
