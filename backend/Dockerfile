# ===== 构建阶段 =====
# 使用 JDK 17 编译（与 pom 的 <java.version>17 对齐，满足 SRS DC-01）
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
# 先复制 pom 以复用依赖层缓存，减少重复下载
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# ===== 运行阶段 =====
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
# 时区统一 Asia/Shanghai（NFR-RE-09）
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Shanghai"
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
COPY --from=builder /build/target/clawbot-backend.jar ./app.jar
EXPOSE 8080
# 生产默认 prod profile：占位符缺失即 Fail-Fast（AC-F3）
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod}"]
