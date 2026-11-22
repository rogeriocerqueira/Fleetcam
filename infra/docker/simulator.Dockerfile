# Build first: ./gradlew :device-simulator:installDist
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/install/device-simulator/ /app/
ENTRYPOINT ["/app/bin/device-simulator"]
