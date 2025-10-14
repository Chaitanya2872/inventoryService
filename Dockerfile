# === Stage 1: Build the JAR ===
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy everything in repo (pom.xml, src, parent POM if present)
COPY . .

# Download dependencies first (cache)
RUN mvn dependency:go-offline -B

# Build the fat JAR
RUN mvn clean package -DskipTests

# === Stage 2: Run the JAR ===
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app

# Copy the JAR from builder
COPY --from=builder /app/target/*.jar app.jar


# Expose port
EXPOSE 8082

# Start app
ENTRYPOINT ["java","-jar","app.jar"]
