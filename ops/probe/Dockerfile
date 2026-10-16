# Probe image: layered Spring Boot jar on pinned Temurin 25 JRE, non-root, read-only-root friendly.
FROM eclipse-temurin@sha256:fd4b1c02c64eef378212e23656fc0b0caa41a357876fa0b60e51ceda94a3db64 AS extract
WORKDIR /build
COPY probe-core/build/libs/probe-core-0.0.1.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination layers

FROM eclipse-temurin@sha256:fd4b1c02c64eef378212e23656fc0b0caa41a357876fa0b60e51ceda94a3db64
RUN groupadd -g 10001 app && useradd -u 10001 -g 10001 -M -s /usr/sbin/nologin app
WORKDIR /app
COPY --from=extract /build/layers/dependencies/ ./
COPY --from=extract /build/layers/spring-boot-loader/ ./
COPY --from=extract /build/layers/snapshot-dependencies/ ./
COPY --from=extract /build/layers/application/ ./
USER 10001:10001
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]
