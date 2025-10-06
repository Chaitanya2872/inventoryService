FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY target/inventory-service-1.0.0.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
