FROM amazoncorretto:21-alpine AS build
WORKDIR /app
RUN apk add --no-cache bash
COPY . .
RUN chmod +x build.sh && ./build.sh

FROM amazoncorretto:21-alpine
WORKDIR /app
COPY --from=build /app/build/ruangpay.jar app.jar
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
