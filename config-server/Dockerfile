FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8888
ENTRYPOINT ["java", "-Xmx384m", "-jar", "app.jar"]