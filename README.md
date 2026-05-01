# MyGame

메이플스토리풍 2D MMO를 만들며 **Java 언어와 객체지향을 학습**하는 프로젝트입니다.

- **Server**: 순수 Java 21 + Java-WebSocket + Jackson + JDBC/H2 (Spring 미사용, 학습 목적)
- **Client**: Phaser 3 + TypeScript + Vite
- **DB**: H2 파일 모드 + Flyway-style 마이그레이션 (직접 구현)
- **통신**: WebSocket + JSON 패킷
- **백오피스**: 순수 `com.sun.net.httpserver` + htmx (별도 프레임워크 없음)

자세한 방향성/로드맵은 [CLAUDE.md](./CLAUDE.md) 참고.
구현 상세 문서는 [docs/](./docs/README.md) 참고.

---

## 현재 진행 상황

**Phase A~K + 백오피스 Phase 1~4 완료**

- A·B 에코 + Tiled 타일맵 / C 멀티플레이어 위치 동기화 / D 다중 맵 + 포털
- E 몬스터 AI(State 패턴) / F 공격·데미지(Command) / G 경험치·레벨업(Observer/EventBus)
- H 아이템 드롭·인벤토리(Factory) / I 장비·스탯(Decorator) / J 채팅(일반·귓속말)
- K 계정 인증 + JDBC 영속화 / 운영자 대시보드(킥·공지·메소·EXP 조정·강제 저장)
- 상점 카탈로그·아이템 템플릿·몬스터 템플릿 + 드롭 테이블 DB 화 + 핫 리로드
- 클라이언트 WS 패킷 디버그 패널 (`?debug=ws`)

다음 후보: Phase L 채널 분리 / Phase M Spring Boot 점진 마이그레이션.

---

## 실행 방법

### 1. 서버 실행
```bash
cd server
./gradlew run
```

기동되는 서비스:
- WebSocket 게임 서버: `ws://localhost:9999`
- 백오피스 HTTP: `http://localhost:8088/admin` (기본 admin/admin — `ADMIN_BOOTSTRAP_PASSWORD` 환경변수로 변경)

데이터 파일은 `~/mygame-data/mygame.mv.db` 에 H2 파일로 저장됩니다.

### 2. 클라이언트 실행
새 터미널에서:
```bash
cd client
npm install
npm run dev
```
브라우저에서 Vite 안내 주소(기본 `http://localhost:5173`) 접속 → 회원가입 → 로그인 → 게임 시작.

---

## 인게임 조작

| 키 | 동작 |
|---|---|
| ←/→ | 좌우 이동 |
| ↑ | 점프 (포털 위면 맵 이동) |
| Space | 기본 공격 |
| 1 / 2 / 3 | 스킬 사용 (Power Strike / Triple Blow / Recovery) |
| Q / E | 인벤토리 첫 장비 착용 / 전체 해제 |
| I / S | 인벤토리 / 스탯 창 토글 |
| F | 가까운 NPC 와 대화(상점) |
| Enter | 채팅 ( `/w 닉네임 메시지` 로 귓속말) |
| Esc | 열린 패널 닫기 |

---

## 디버깅 도구

### WS 패킷 디버그 패널
URL 끝에 `?debug=ws` 추가 → 우하단에 송수신 패킷 실시간 패널 노출.
- send(주황 →) / recv(파랑 ←) 색 구분, 시퀀스 번호 누적
- 행 클릭 → payload JSON 펼침
- MOVE 류는 기본 필터(헤더 토글로 노출)
- Clear 누를 때까지 무제한 누적

### H2 파일 직접 조회
서버가 떠 있는 동안에도 외부 클라이언트(DBeaver 등)로 동시 접속 가능:
```
JDBC URL: jdbc:h2:file:~/mygame-data/mygame;AUTO_SERVER=TRUE
User: sa
Password: (빈 값)
```

### 백오피스
`http://localhost:8088/admin` — 접속자 모니터·플레이어 메소/EXP 조정·강제 저장·시스템 공지·계정 정지/복구·비밀번호 리셋·상점/아이템/몬스터 템플릿 편집·감사 로그.

---

## 디렉토리

```
server/                      # 순수 Java 게임 서버 + 백오피스
  src/main/java/mygame/
    network/                 # WebSocket / 패킷 디스패처 / 핸들러
    game/                    # 도메인 (World, GameMap, Player, Monster, Skill, Item, Shop)
      event/                 # EventBus + GameEvent (sealed)
    db/                      # JDBC Repository + SqlRunner (자체 JdbcTemplate 학습 구현)
    admin/                   # 백오피스 — handlers / commands / view / auth / audit
    auth/                    # 게임 계정 인증

client/                      # Phaser 3 + TS 클라이언트
  src/
    scenes/                  # Phaser Scene + 텍스처 팩토리 + 맵 컨트롤러
    entities/                # 원격 플레이어·몬스터·NPC·드롭 아이템 스프라이트
    game/                    # EntityRegistry (스프라이트 라이프사이클 매니저)
    ui/                      # HUD / 인벤·장비·상점·스탯 패널 / 채팅 / 이펙트
    network/                 # WebSocketClient (옵저버 패턴)
    debug/                   # PacketLogger (?debug=ws)
    auth/                    # 로그인 화면

docs/                        # 아키텍처·도메인·프로토콜·디자인 패턴 학습 노트
```

---

## 학습 메모

각 Phase 에서 적용한 디자인 패턴과 의도는 [docs/08-design-patterns.md](./docs/08-design-patterns.md) 와
각 Phase 진행 기록은 [docs/09-development-history.md](./docs/09-development-history.md) 에서 확인할 수 있습니다.

본 프로젝트의 핵심 학습 원칙은 [CLAUDE.md](./CLAUDE.md) 의 "학습 원칙" 섹션 참고:
1. Spring 의 마법을 금지하고 같은 기능을 직접 구현 (예: `db/SqlRunner` ≈ `JdbcTemplate`)
2. 라이브러리 도입 전에 이유를 적기
3. 성급한 추상화 금지 (Rule of Three)
4. 테스트 가능한 설계 (인터페이스로 격리)

---

## 저작권

본 프로젝트는 **개인 학습용**입니다. 메이플스토리는 NX 의 지적재산이며, 본 코드베이스는
상업적 이용·배포·공식 에셋 사용을 금지합니다. 외부 공개 시 모든 에셋·명칭·맵을 자체 제작
또는 CC0 에셋으로 교체해야 합니다.
