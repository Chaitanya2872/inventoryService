# ---- Stage 1: Build the JAR ----
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy only pom.xml first (for better caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy all project files
COPY . .

# Build the Spring Boot JAR (skip tests for faster build)
RUN mvn clean package -DskipTests

# ---- Stage 2: Run the JAR ----
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app

# Copy the specific JAR from builder stage
COPY --from=builder /app/target/inventory-service-1.0.0.jar app.jar

# Expose the app port
EXPOSE 8082

# Start the Spring Boot application
ENTRYPOINT ["java", "-jar", "app.jar"]
