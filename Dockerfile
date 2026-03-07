FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml ./
COPY src ./src

RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

ENV PORT=10000
ENV DATA_DIR=/var/data/spectrum-code-forge

COPY --from=build /app/target/classes ./target/classes

EXPOSE 10000

CMD ["java", "-cp", "target/classes", "com.spectrumforge.SpectrumCodeForgeApplication"]
