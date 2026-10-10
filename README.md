# MOAMOA Server

Team AnI의 MOAMOA Spring Boot · Kotlin 서버 저장소입니다.

현재는 애플리케이션 시작과 Context 로딩 테스트를 제공하는 최소 서버 골격입니다. 기능별 패키지는 실제 개발 Issue에서 추가합니다.

## 목차

- [우리 팀 기여 가이드](#우리-팀-기여-가이드)
- [GitHub 컨벤션](#github-컨벤션)
- [프로젝트 아키텍처](#프로젝트-아키텍처)
- [코드 컨벤션 가이드라인](#코드-컨벤션-가이드라인)
- [기타](#기타)

---

## 우리 팀 기여 가이드

### 1. 개발 환경 준비

#### 1-1. 요구 버전

| 항목 | 버전 | 기준 |
| --- | --- | --- |
| Java (JDK) | 21 | `build.gradle.kts`의 Java Toolchain |
| Kotlin | 2.3.21 | Kotlin Gradle Plugin |
| Spring Boot | 4.1.1 | Spring Boot Gradle Plugin |
| Gradle | 9.3.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Swagger / OpenAPI | springdoc-openapi 3.1.1 | `build.gradle.kts` |

Spring MVC, Spring Data JPA, Bean Validation, Kotest BehaviorSpec 기반 테스트를 사용합니다. 서버 실행에는 PostgreSQL이 필요하며, 테스트는 `test` Profile의 메모리 H2를 사용해 별도 DB 설치 없이 실행할 수 있습니다.

#### 1-2. 환경 및 IDE 설정

```bash
java -version
./gradlew --version
```

권장 IDE는 **IntelliJ IDEA**입니다. JDK와 Gradle JVM을 Java 21로 지정합니다. Gradle은 저장소의 Wrapper를 사용하므로 별도로 설치할 필요가 없습니다. 첫 실행에는 Gradle과 의존성을 내려받을 네트워크 연결이 필요합니다.

현재 `.editorconfig`, ktlint, detekt 설정은 없습니다.

#### 1-3. 프로젝트 clone 및 실행

```bash
# 원본 저장소를 개인 계정으로 Fork 한 뒤 clone
git clone https://github.com/<your-id>/MOAMOA-Server.git
cd MOAMOA-Server

# 원본 저장소를 upstream 으로 등록
git remote add upstream https://github.com/Team-AnI/MOAMOA-Server.git

# 테스트
./gradlew test

# 로컬 PostgreSQL 서버와 moamoa DB를 준비한 뒤 접속 정보 설정
cp .env.example .env
# .env의 DB_URL, DB_USERNAME, DB_PASSWORD를 실제 접속 정보로 수정

# 애플리케이션 실행
./gradlew bootRun
```

기본 포트는 `8080`입니다. 실행 후 다음 주소를 사용할 수 있습니다.

- Swagger UI: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
- OpenAPI JSON: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

현재 비즈니스 API가 없어 Swagger의 API 목록이 비어 있어도 정상입니다. 종료는 실행한 터미널에서 `Ctrl+C`를 누릅니다.

Windows에서는 `./gradlew` 대신 `.\gradlew.bat`을 사용합니다.

```powershell
.\gradlew.bat test
Copy-Item .env.example .env
# .env의 접속 정보를 수정한 뒤 실행
.\gradlew.bat bootRun
```

#### 1-4. 자주 쓰는 명령어

| 명령어 | 설명 |
| --- | --- |
| `./gradlew bootRun` | 서버 실행 |
| `./gradlew test` | Context 로딩 테스트 실행 |
| `./gradlew check` | 검증 실행 (현재 테스트 포함, 별도 정적 분석 없음) |
| `./gradlew build` | 테스트 및 실행 가능한 JAR 빌드 |
| `./gradlew clean test` | 빌드 결과 삭제 후 테스트 실행 |
| `./gradlew tasks` | 사용 가능한 Task 확인 |

#### 1-5. 환경 변수 및 민감 정보

서버 실행에는 다음 DB 설정이 필요합니다. 로컬 개발에서는 `.env.example`을 저장소 루트의 `.env`로 복사하고 실제 접속 정보로 수정합니다. 예제 비밀번호 `change-me`는 실제 DB 비밀번호로 바꿔야 합니다.

| 변수 | 용도 | 예시 |
| --- | --- | --- |
| `DB_URL` | PostgreSQL JDBC 접속 URL | `jdbc:postgresql://localhost:5432/moamoa` |
| `DB_USERNAME` | DB 사용자 이름 | `postgres` |
| `DB_PASSWORD` | DB 비밀번호 | 실제 DB 비밀번호 |

`application.yml`은 실행 작업 디렉터리의 `.env`를 Java Properties 형식으로 읽습니다. 저장소 루트에서 실행하고, IntelliJ 실행 설정의 Working directory도 저장소 루트로 지정합니다.

- 비밀번호, API Key, Access Token, Secret Key 등 민감 정보는 Git에 커밋하지 않습니다.
- `.env`는 Git에서 제외하고, 실제 비밀값이 없는 `.env.example`만 공유합니다.
- 파일은 `KEY=value` 형식을 사용합니다. `export`나 값을 감싸는 따옴표를 사용하지 않습니다. 따옴표는 값에 포함되며, 리터럴 역슬래시는 `\\`로 작성합니다.
- OS 환경변수로 주입한 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`는 `.env` 값보다 우선합니다. 배포 환경에서는 `.env` 없이 환경변수로 설정할 수 있습니다.
- DB 설정에 기본값은 없습니다. `.env` 파일 자체는 선택 사항이지만, 서버 실행 시 세 변수는 모두 제공해야 합니다.
- `./gradlew test`는 테스트 전용 H2 설정을 사용하므로 `.env`와 PostgreSQL 서버 없이 실행할 수 있습니다.

### 2. 작업 흐름

1. 작업 전 **Issue**를 생성하고 담당자를 지정합니다. ([이슈 등록 방법](#1-이슈issue-등록))
2. 최신 `main`을 동기화합니다.

   ```bash
   git checkout main
   git pull upstream main
   ```

3. Issue 번호를 포함한 브랜치를 생성합니다.
4. 작업 후 프로젝트에 마련된 테스트와 검증을 수행하고 결과를 기록합니다.
5. 변경 사항을 커밋하고 본인 Fork로 push합니다.
6. `upstream/main`을 대상으로 **Pull Request**를 생성합니다.
7. 리뷰어 **1명 이상 Approve** 후 머지합니다.

### 3. 브랜치 규칙

| 브랜치 | 용도 | 예시 |
| --- | --- | --- |
| `main` | 배포 가능한 안정 브랜치, 직접 push 금지 | - |
| `feature/#이슈번호-기능이름` | 새로운 기능 | `feature/#12-create-group` |
| `fix/#이슈번호-설명` | 버그 수정 | `fix/#15-invalid-token` |
| `refactor/#이슈번호-수정범위` | 리팩터링 | `refactor/#20-member-service` |
| `chore/#이슈번호-설명` | 빌드, 설정, 의존성 | `chore/#3-update-gradle` |
| `docs/#이슈번호-설명` | 문서 | `docs/#1-readme` |
| `test/#이슈번호-설명` | 테스트 | `test/#23-group-service` |

### 4. 이슈 / 커밋 / PR 작성

이슈, 커밋 메시지, PR 메시지는 [GitHub 컨벤션](#github-컨벤션) 섹션을 따릅니다.

### 5. 코드 리뷰

- 리뷰 요청을 받으면 가능한 **24시간 이내**에 응답합니다.
- 코멘트는 근거와 함께 정중하게 작성합니다.
- 필수 수정과 제안을 구분합니다.
  - `[필수]`: 머지 전에 반드시 수정해야 하는 사항
  - `[제안]`: 더 나은 구현을 위한 제안
  - `[질문]`: 의도나 설계를 확인하기 위한 질문
- 모든 필수 코멘트가 해결(Resolve)된 후 머지합니다.
- 단순 취향보다 프로젝트 규칙, 버그 가능성, 유지보수성, 테스트 가능성을 기준으로 리뷰합니다.

---

## GitHub 컨벤션

### 1. 이슈(Issue) 등록

> 모든 작업은 **이슈 카드 등록부터** 시작합니다.  
> 기능 개발, 버그 수정, 리팩터링, 테스트, 문서 및 설정 변경도 이슈로 관리합니다.  
> 이슈 번호는 브랜치, 커밋, PR에 사용합니다.

#### 1-1. 등록 방법

1. 저장소의 [Issues 탭](https://github.com/Team-AnI/MOAMOA-Server/issues) → **New issue**를 클릭합니다.
2. 아래 형식에 맞춰 제목과 본문을 작성합니다.
3. 오른쪽 사이드바에서 필요한 항목을 설정합니다.
   - **Assignees**: 작업 담당자
   - **Labels**: 작업 종류
   - **Projects**: 프로젝트 보드에 추가하는 경우
   - **Milestone**: 스프린트/버전을 사용하는 경우
4. **Submit new issue**로 등록합니다.
5. 생성된 이슈 번호를 사용해 브랜치를 생성합니다.

GitHub CLI를 사용할 수도 있습니다.

```bash
gh issue create \
  --repo Team-AnI/MOAMOA-Server \
  --title "[DOCS] 서버 코드 스타일 가이드라인 정의" \
  --label documentation \
  --assignee @me
```

#### 1-2. 제목

```text
[TYPE] 작업 내용 요약
```

| TYPE | 용도 | 예시 |
| --- | --- | --- |
| `[FEAT]` | 새로운 기능 | `[FEAT] 모임 생성 API 구현` |
| `[FIX]` | 버그 수정 | `[FIX] 만료 토큰 검증 오류 수정` |
| `[REFACTOR]` | 리팩터링 | `[REFACTOR] MemberService 책임 분리` |
| `[DOCS]` | 문서, 가이드라인 | `[DOCS] 서버 코드 스타일 가이드라인 정의` |
| `[CHORE]` | 빌드, 설정, 의존성 | `[CHORE] Gradle 설정 변경` |
| `[TEST]` | 테스트 | `[TEST] 모임 생성 서비스 테스트 작성` |
| `[CI]` | CI/CD | `[CI] PR 검증 워크플로 추가` |

#### 1-3. 본문 템플릿

```markdown
## 📝 설명
- 어떤 작업인지, 왜 필요한지 적습니다.

## ✅ 할 일
- [ ] 세부 작업 1
- [ ] 세부 작업 2

## 🙋 참고 사항
- 관련 문서, API 명세, 논의가 필요한 부분 등을 적습니다.
```

버그 이슈는 아래 항목을 추가합니다.

```markdown
## 🐞 재현 방법
1. ...
2. ...

## 기대 동작 / 실제 동작
- 기대: ...
- 실제: ...

## 환경
- 브랜치:
- 실행 환경:
- 관련 로그:
```

#### 1-4. 라벨

| 라벨 | 용도 |
| --- | --- |
| `enhancement` | 새로운 기능 및 개선 |
| `bug` | 버그 |
| `documentation` | 문서 및 가이드라인 |
| `question` | 논의/질문이 필요한 이슈 |
| `help wanted` | 도움이 필요한 이슈 |
| `good first issue` | 처음 합류한 팀원이 하기 좋은 이슈 |

#### 1-5. 예시: 코드 스타일 가이드라인 이슈

```markdown
제목: [DOCS] 서버 코드 스타일 가이드라인 정의
라벨: documentation
담당자: @담당자

## 📝 설명
- Kotlin/Spring Boot 코드 스타일을 통일하기 위해 팀 컨벤션을 정하고 README에 정리합니다.

## ✅ 할 일
- [ ] 패키지 구성 규칙
- [ ] 클래스/함수/변수 네이밍 규칙
- [ ] DTO / Entity / Service 네이밍 규칙
- [ ] ktlint / detekt 적용 여부 결정
- [ ] README "코드 컨벤션 가이드라인" 섹션 작성

## 🙋 참고 사항
- Kotlin Coding Conventions
- Spring Boot Reference Documentation
```

#### 1-6. 이슈 종료

- PR 본문에 `close #이슈번호`를 적으면 PR이 머지될 때 이슈가 자동으로 닫힙니다.
- 작업하지 않기로 한 이슈는 사유를 남기고 **Close as not planned**로 닫습니다.

### 2. 커밋 메시지 컨벤션

#### 2-1. 형식

```text
<type>: <subject> (#이슈번호)

<body>

<footer>
```

- **header** (`<type>: <subject> (#이슈번호)`): 필수
- **body**: 선택. 변경 이유가 header만으로 충분하지 않을 때 작성
- **footer**: 선택. 관련 이슈 처리, Breaking Change 등을 작성

#### 2-2. type

| type | 설명 | 예시 |
| --- | --- | --- |
| `feat` | 새로운 기능 추가 | `feat: 모임 생성 API 구현 (#12)` |
| `fix` | 버그 수정 | `fix: 만료 토큰 검증 오류 수정 (#15)` |
| `refactor` | 동작 변경 없는 코드 구조 개선 | `refactor: MemberService 책임 분리 (#20)` |
| `style` | 포맷팅, import 정리 등 | `style: Kotlin 코드 포맷 적용 (#21)` |
| `test` | 테스트 코드 추가/수정 | `test: 모임 생성 서비스 테스트 추가 (#23)` |
| `docs` | 문서 수정 | `docs: README 기여 가이드 작성 (#1)` |
| `chore` | 빌드, 패키지, 설정, 의존성 | `chore: Gradle 설정 변경 (#3)` |
| `ci` | CI/CD 설정 변경 | `ci: PR 검증 워크플로 추가 (#5)` |
| `rename` | 파일/패키지명 변경 또는 이동 | `rename: member 패키지 구조 변경 (#24)` |
| `remove` | 파일/코드 삭제 | `remove: 사용하지 않는 설정 제거 (#25)` |

#### 2-3. 작성 규칙

- **subject**
  - 한글로 작성하고 50자 이내로 간결하게 씁니다.
  - `~ 구현`, `~ 수정`, `~ 추가`처럼 명사형으로 끝냅니다.
  - 마침표를 붙이지 않습니다.
  - 끝에 관련 이슈 번호를 `(#이슈번호)`로 붙입니다.
- **body**
  - header와 한 줄 띄우고 작성합니다.
  - **무엇을, 왜** 변경했는지 작성합니다.
  - 구현 세부사항 자체는 코드로 확인 가능하므로 불필요하게 길게 적지 않습니다.
- **footer**
  - 이슈 처리: `Close #12`, `Fixes #15`, `Ref #20`
  - 하위 호환이 깨지는 변경: `BREAKING CHANGE: <설명>`
- 하나의 커밋에는 하나의 변경 의도만 담습니다.

#### 2-4. 예시

```text
feat: 모임 생성 API 구현 (#12)

- 모임 생성 요청을 검증하고 모임 정보를 저장
- 생성자를 기본 모임장으로 등록

Close #12
```

### 3. PR 메시지 컨벤션

#### 3-1. 제목

커밋 메시지 header와 같은 형식으로 작성합니다.

```text
<type>: <subject> (#이슈번호)
```

예시:

```text
feat: 모임 생성 API 구현 (#12)
```

#### 3-2. 본문 템플릿

```markdown
## 📌 관련 이슈
- close #이슈번호

## ✨ 작업 내용
- 작업한 내용을 요약합니다.

## 🧪 테스트
- 실행한 테스트와 검증 결과를 적습니다.

## 💬 리뷰 요청 사항
- 리뷰어가 중점적으로 봐야 할 부분이나 고민한 점을 적습니다.

## ✅ 체크리스트
- [ ] `./gradlew test` 통과
- [ ] `./gradlew check` 통과
- [ ] 민감 정보가 포함되지 않았는지 확인
- [ ] 관련 이슈 연결
- [ ] 셀프 리뷰 완료
```

#### 3-3. 작성 규칙

- base 브랜치는 `upstream/main`입니다.
- 하나의 PR에는 하나의 목적만 담고 가능한 작은 단위로 올립니다.
- 작업 중인 PR은 **Draft PR**로 올리고 리뷰 가능할 때 Ready for review로 전환합니다.
- 담당자(Assignee)는 본인, 리뷰어(Reviewers)는 1명 이상 지정합니다.
- 머지는 리뷰어 1명 이상 Approve 후 작성자가 직접 합니다.
- API 변경이 있다면 요청/응답 또는 명세 변경 내용을 PR에 함께 남깁니다.
- DB Schema 변경이 있다면 Migration과 영향 범위를 함께 확인합니다.

---

## 프로젝트 아키텍처

Base package는 `com.teamani.moamoa`입니다.

```text
src/
├── main/
│   ├── kotlin/com/teamani/moamoa/
│   │   └── MoamoaApplication.kt
│   └── resources/
│       └── application.yml
└── test/
    └── kotlin/com/teamani/moamoa/
        └── MoamoaApplicationTests.kt
```

- `MoamoaApplication`: Spring Boot 시작점이며, 해당 패키지 아래를 기본 컴포넌트 스캔 범위로 사용합니다.
- `application.yml`: 애플리케이션 이름 `moamoa-server`, 로컬 `.env` 로딩과 PostgreSQL 접속 정보를 설정합니다.
- `MoamoaApplicationTests`: `test` Profile과 H2로 Application Context 로딩을 확인합니다.

현재 Controller, Service, Repository, Entity, DTO와 비즈니스 요청 처리 흐름은 없습니다. 필요한 패키지와 코드는 실제 기능 개발 Issue에서 추가합니다. Swagger는 springdoc 자동 설정을 사용하며 별도 설정 클래스가 없습니다.

---

## 코드 컨벤션 가이드라인

아래 기본 가이드는 첨부 템플릿을 유지합니다. 아직 구현되지 않은 레이어의 규칙은 향후 개발 시의 권장사항이며, 기존 구현이 있다는 의미는 아닙니다.

### 1. 기본 원칙

- Kotlin 공식 Coding Conventions를 기본으로 따릅니다.
- 프로젝트에 `.editorconfig`, ktlint, detekt 설정이 있다면 해당 설정을 최우선으로 따릅니다.
- 기존 코드 스타일과 다른 개인 취향의 리팩터링은 기능 PR에 섞지 않습니다.
- 한 클래스가 여러 책임을 갖지 않도록 역할을 분리합니다.
- 불필요한 추상화보다 현재 요구사항을 해결하는 단순한 구현을 우선합니다.

### 2. Kotlin

- 변경되지 않는 값은 `var`보다 `val`을 우선합니다.
- Kotlin의 Null Safety를 활용하고 불필요한 `!!` 사용을 피합니다.
- 함수와 변수는 `camelCase`, 클래스와 인터페이스는 `PascalCase`를 사용합니다.
- 상수는 프로젝트의 기존 규칙을 따르며 일반적으로 `UPPER_SNAKE_CASE`를 사용합니다.
- 와일드카드 import는 사용하지 않습니다.
- 의미 없는 축약어보다 역할이 드러나는 이름을 사용합니다.

### 3. Spring Boot

- 의존성 주입은 생성자 주입을 기본으로 합니다.
- Controller에는 HTTP 요청/응답 처리 책임만 둡니다.
- 비즈니스 규칙은 Controller에 직접 작성하지 않습니다.
- API 응답에는 Entity를 직접 노출하기보다 DTO 사용을 권장합니다.
- TODO: DTO·Entity 명명, Service·Repository 구성, 예외 처리 방식과 공통 API 응답 형식은 실제 기능 구현 시 정리합니다.
- 트랜잭션 범위는 비즈니스 작업 단위를 기준으로 명확하게 설정합니다.

### 4. 테스트

- 기능 추가 및 버그 수정 시 가능한 경우 관련 테스트를 함께 작성합니다.
- 버그 수정은 재현 가능한 테스트를 먼저 추가하는 것을 권장합니다.
- 외부 시스템과 무관한 비즈니스 로직은 가능한 빠르고 독립적으로 검증할 수 있게 작성합니다.
- 테스트 클래스는 `MoamoaApplicationTests`처럼 `Tests` 접미사를, 메서드는 `contextLoads`처럼 `camelCase`를 사용합니다. 현재 테스트는 JUnit Jupiter의 `@Test`를 사용합니다.

---

## 기타

### 개발환경 및 인프라

- 서버는 PostgreSQL을 사용하며, 접속 정보는 `.env` 또는 환경변수로 제공합니다. DB 서버와 대상 DB는 실행 전에 준비해야 합니다.
- 테스트는 `application-test.yml`의 메모리 H2를 사용합니다. 별도 서버와 접속 정보가 필요 없으며 데이터는 영구 저장되지 않습니다.
- H2 콘솔, 샘플 테이블, 초기 데이터는 구성하지 않았습니다.
- Kotlin `jvm`, `plugin.spring`, `plugin.jpa`를 사용합니다. Spring 프록시와 JPA 기본 생성자를 지원하고, JPA 타입의 프록시 생성을 위한 `allOpen` 설정을 포함합니다.
- Kotlin 표준 라이브러리는 Kotlin Gradle Plugin이 추가합니다. `kotlin-reflect`와 Jackson 3 Kotlin 모듈은 직접 선언합니다.
- Spring Boot dependency management로 관리되는 라이브러리에는 개별 버전을 지정하지 않습니다. springdoc만 `3.1.1`을 명시합니다.
- `.gitignore`는 Gradle·Kotlin 빌드 캐시, 빌드 산출물, IntelliJ 파일과 `.env`를 제외합니다. Gradle Wrapper는 저장소에 포함합니다.
- 테스트용 `test` Spring Profile을 사용합니다. 별도 CI/CD, Docker 구성은 없습니다.

TODO: 운영 DB 인프라 및 배포 환경은 해당 요구사항이 확정되는 Issue에서 결정하고 문서화합니다.

### 문서 유지 원칙

README는 실제 코드와 설정을 기준으로 유지합니다.

다음 항목이 변경되면 README도 함께 확인합니다.

- Java / Kotlin / Spring Boot / Gradle 버전
- 실행 및 테스트 명령어
- 패키지 또는 아키텍처 구조
- 환경 변수
- CI/CD
- 브랜치 및 GitHub 협업 규칙
