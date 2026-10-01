FROM eclipse-temurin:21-alpine
COPY target/knote-1.0.0.jar /app/knote.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/knote.jar"]
