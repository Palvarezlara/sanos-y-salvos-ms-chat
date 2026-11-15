FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
# las pruebas corren aqui: un cambio roto no debe llegar a la imagen
RUN mvn -B package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build /app/target/ms-chat-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8084
# HTTP real contra /actuator/health (incluye el estado de los consumidores SQS); la imagen no trae curl.
# SPRING_MVC_SERVLET_PATH=/api/v1 en el compose y en ECS.
HEALTHCHECK --interval=15s --timeout=10s --start-period=60s --retries=5 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/localhost/8084 && printf 'GET /api/v1/actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
ENTRYPOINT ["java", "-jar", "app.jar"]
