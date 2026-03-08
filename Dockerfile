FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml ./
COPY src ./src

RUN mvn -q -DskipTests package dependency:copy-dependencies

FROM eclipse-temurin:17-jre
WORKDIR /app

ENV PORT=10000

COPY --from=build /app/target/classes ./target/classes
COPY --from=build /app/target/dependency ./target/dependency

EXPOSE 10000

CMD ["java", "-cp", "target/classes:target/dependency/*", "com.spectrumforge.SpectrumCodeForgeApplication"]
