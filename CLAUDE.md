# MyGame — 메이플스토리풍 2D MMO (Java 학습 프로젝트)

## 프로젝트 목적

**Java 언어와 객체지향 설계를 게임 개발을 통해 학습**하는 프로젝트입니다.
단순한 학습용 예제가 아니라, 실제 동작하는 멀티플레이어 2D 횡스크롤 게임(메이플스토리 스타일)을 만들면서 다음을 체득합니다.

- 객체지향 설계 (캡슐화, 상속, 다형성, 인터페이스)
- 디자인 패턴 (Command, State, Observer, Factory, Strategy)
- 동시성 (Thread, ExecutorService, ConcurrentHashMap, synchronized)
- 네트워크 프로그래밍 (WebSocket, JSON 패킷)
- 레이어드 아키텍처 (네트워크 / 도메인 / 영속성 분리)
- DB 연동 (JDBC → 필요 시 JPA)
- 최종적으로 SpringBoot로의 점진적 마이그레이션

사용자는 이미 개발자로 일하고 있으며, **Java는 신규 학습 언어**입니다.
게임 자체의 완성도보다 **코드 품질과 학습 가치**가 우선순위입니다.

---

## 기술 스택

### Server (순수 Java, Spring 미사용)
- **언어**: Java 21 (LTS)
- **빌드**: Gradle
- **WebSocket**: `org.java-websocket:Java-WebSocket`
- **JSON**: Jackson
- **DB**: H2 (임베디드) + 순수 JDBC
- **동시성**: `ScheduledExecutorService` 기반 게임 루프 (30 tick/sec)
- **Spring 금지**: 학습 목적상 DI, AOP 등 Spring 마법을 직접 구현해봐야 본질을 이해할 수 있음. Phase E 이후 점진 마이그레이션 예정.

### Client (웹)
- **게임 엔진**: Phaser 3
- **언어**: TypeScript
- **번들러**: Vite
- **통신**: 브라우저 내장 WebSocket API (JSON 패킷)
- **맵 에디터**: Tiled (`.tmx` → JSON export)

### 에셋
- **저작권 주의**: 메이플스토리 실제 에셋은 학습용으로만. 공개 배포 시 전부 교체.
- 무료 대체: Kenney.nl, itch.io (free, platformer tag), OpenGameArt

---

## 프로젝트 구조 (목표)

```
MyGame/
├── CLAUDE.md
├── README.md
├── server/                          # 순수 Java WebSocket 서버
│   ├── build.gradle
│   └── src/main/java/mygame/
│       ├── Main.java
│       ├── network/                 # 통신 계층
│       │   ├── GameServer.java      # WebSocketServer 상속
│       │   ├── PacketDispatcher.java
│       │   └── packets/             # 패킷 DTO
│       ├── game/                    # 도메인 계층
│       │   ├── World.java
│       │   ├── Channel.java
│       │   ├── GameMap.java
│       │   ├── entity/
│       │   │   ├── Entity.java
│       │   │   ├── Player.java
│       │   │   └── Monster.java
│       │   ├── ai/                  # State Pattern
│       │   └── GameLoop.java
│       ├── command/                 # Command Pattern
│       ├── item/                    # 아이템/인벤토리
│       ├── db/                      # JDBC Repository
│       └── util/
└── client/                          # Phaser 3 + TypeScript
    ├── package.json
    ├── vite.config.ts
    ├── index.html
    └── src/
        ├── main.ts
        ├── scenes/                  # LoginScene, GameScene
        ├── entities/                # Player.ts, Monster.ts
        ├── network/                 # WebSocketClient.ts
        └── assets/
```

---

## 개발 로드맵 (Phase 단위로 학습 주제와 매핑)

| Phase | 목표 | 학습 Java/OOP 주제 |
|-------|------|-------------------|
| **A** | Java-WebSocket 에코 + Phaser 캐릭터 움직이기 | 클래스/인터페이스, 콜백, 기본 네트워킹 |
| **B** | 중력·점프, Tiled 타일맵 로드 | 클라이언트 물리, JSON 파싱 |
| **C** | 멀티플레이어 위치 동기화 | `ConcurrentHashMap`, 스레드 안전성, 브로드캐스트 |
| **D** | 맵 클래스, 포털로 맵 이동 | 캡슐화, 컬렉션 설계 |
| **E** | 몬스터 스폰 + AI (State Machine) | **State 패턴**, 추상 클래스, 게임 루프 |
| **F** | 공격/데미지/HP 시스템 | **Command 패턴**, 다형성 (스킬) |
| **G** | 경험치/레벨업 | **Observer 패턴** (이벤트), 불변 객체 |
| **H** | 아이템 드롭 + 인벤토리 | **Factory 패턴**, 제네릭 |
| **I** | 장비 착용 + 스탯 계산 | **Decorator 패턴**, 불변 스탯 계산 |
| **J** | 채팅 (일반/귓속말/맵) | 라우팅, 패킷 타입 확장 |
| **K** | DB 연동 (JDBC) | Repository 패턴, 트랜잭션 |
| **L** | 채널 분리 + 로그인 서버 | 프로세스 간 통신, 인증 |
| **M** | (선택) SpringBoot 마이그레이션 | DI, Spring WebSocket, JPA |

각 Phase 완료 시 **무엇을 배웠는지 회고 노트**를 남기면 학습 효과가 배가됩니다.

---

## 코딩 규약

### Java
- **Java 21 기능 적극 활용**: `record`, `sealed`, pattern matching, `var` (지역 변수)
- **네이밍**: 클래스 `PascalCase`, 메서드/필드 `camelCase`, 상수 `UPPER_SNAKE_CASE`
- **패키지**: 기능/도메인 단위로 분리 (type 단위 분리 금지)
- **불변성 우선**: DTO·값 객체는 `record`로 작성, 컬렉션은 `List.copyOf` 등으로 방어적 복사
- **동시성**: 공유 상태는 반드시 `ConcurrentHashMap` 또는 `synchronized` 명시
- **주석**: 한국어, **왜(why)**에 집중. 무엇(what)은 코드가 말하게 할 것.
- **파일 크기**: 한 파일 200~400줄 권장, 800줄 초과 금지

### TypeScript (클라이언트)
- **타입 명시**: `any` 사용 금지, 서버 DTO와 대응하는 타입 유지
- **불변성**: `readonly` 적극 사용
- **씬 단위 분리**: Phaser Scene 하나당 하나의 파일

### 공통
- **커밋 메시지**: 한국어, `feat:`, `fix:`, `refactor:`, `docs:` 프리픽스
- **문서/주석**: 한국어
- **변수/함수명**: 영어

---

## 실행 방법 (Phase A 시작 후 채울 것)

```bash
# 서버
cd server
./gradlew run

# 클라이언트
cd client
npm install
npm run dev
```

---

## 학습 원칙

1. **Spring의 마법을 금지한다**: DI, AOP, `@RestController` 등을 쓰지 않고 같은 기능을 직접 구현해본다. 그래야 나중에 Spring이 *무엇을 해주는지* 체감할 수 있다.
2. **라이브러리 도입 전에 이유를 적는다**: 새 dependency 추가 시 CLAUDE.md 또는 커밋 메시지에 "왜 필요한가"를 기록.
3. **성급한 추상화 금지**: 3번 반복된 다음에 추상화한다 (Rule of Three).
4. **테스트 가능한 설계**: 네트워크/DB에 직접 의존하지 않도록 인터페이스로 격리.
5. **객체지향 학습 노트**: 각 Phase에서 적용한 디자인 패턴을 `docs/learning-notes/` 에 정리.

---

## 저작권 / 주의사항

- 메이플스토리는 NX의 지적재산입니다. 본 프로젝트는 **개인 학습용**이며, 상업적 이용·배포·공식 에셋 사용은 금지합니다.
- 외부 공개(GitHub Public, 포트폴리오) 시 모든 에셋·명칭·맵을 자체 제작 또는 CC0 에셋으로 교체해야 합니다.

---

## 참고 자료

- **Cosmic** (메이플 서버 에뮬레이터, Java): https://github.com/P0nk/Cosmic
- **BrowserQuest** (Mozilla 2D MMO): 구조 참고
- **Phaser 3 공식 예제**: https://phaser.io/examples
- **Tiled Map Editor**: https://www.mapeditor.org/
- **Java-WebSocket**: https://github.com/TooTallNate/Java-WebSocket

---

## 다음 할 일

- [ ] Phase A 시작: 서버/클라이언트 프로젝트 뼈대 생성
- [ ] Gradle 프로젝트 초기화 (`server/`)
- [ ] Vite + Phaser 3 + TS 초기화 (`client/`)
- [ ] 에코 WebSocket 연결 확인
- [ ] 캐릭터 스프라이트 에셋 1종 확보 (Kenney Platformer Pack 등)
