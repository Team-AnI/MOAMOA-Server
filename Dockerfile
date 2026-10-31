FROM eclipse-temurin:21-jre

# [임시 조치] eMudhra 중간 CA 키 유출 대응, JDK 신뢰 저장소의 해당 루트 인증서 제거 (CVE-2026-114820)
# 조치된 eclipse-temurin 이미지 배포 후 블록 삭제 (https://openjdk.org/emudhra)
RUN set -eu; \
    ROOTS='^Owner: CN=(emSign ECC Root CA - G3|emSign Root CA - G1|emSign Root CA - G2|AAA Certificate Services),'; \
    kt() { keytool -J-Duser.language=en "$@" -cacerts -storepass changeit; }; \
    kt -list -v > /tmp/ks.txt; \
    awk -v re="$ROOTS" '/^Alias name: /{a=substr($0,13)} $0 ~ re {print a}' /tmp/ks.txt \
      | while IFS= read -r a; do echo "removing: $a"; kt -delete -alias "$a"; done; \
    kt -list -v > /tmp/ks.txt; \
    if grep -Eq "$ROOTS" /tmp/ks.txt; then echo "eMudhra roots still present" >&2; exit 1; fi; \
    rm -f /tmp/ks.txt

WORKDIR /app

COPY build/libs/app.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
