FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY kserve/java-transformer/pom.xml .
COPY kserve/java-transformer/src ./src
RUN mvn package -DskipTests

FROM registry.access.redhat.com/ubi8/openjdk-17-runtime:1.20
ENV LANGUAGE='en_US:en'
COPY --from=build --chown=185 /workspace/target/quarkus-app/lib/ /deployments/lib/
COPY --from=build --chown=185 /workspace/target/quarkus-app/*.jar /deployments/
COPY --from=build --chown=185 /workspace/target/quarkus-app/app/ /deployments/app/
COPY --from=build --chown=185 /workspace/target/quarkus-app/quarkus/ /deployments/quarkus/
EXPOSE 8080
USER 185
ENTRYPOINT ["java", "-jar", "/deployments/quarkus-run.jar"]
