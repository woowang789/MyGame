# 01 · 아키텍처 개요

## 1. 런타임 토폴로지

```
┌──────────────────────────────────────────────────────────────────────────┐
│                              클라이언트(브라우저)                        │
│                                                                          │
│  ┌────────────────┐   ┌──────────────┐   ┌─────────────────────────┐    │
│  │ Phaser 3 Scene │ ⇄ │ MapController│   │ DOM HUD (index.html)    │    │
│  │ (game render)  │   │ TileMap+포털 │   │ HudView · ChatController│    │
│  └────────┬───────┘   └──────────────┘   │ EffectFactory · Pickup  │    │
│           │                              └─────────────────────────┘    │
│           ▼                                                              │
│  ┌─────────────────────────────────────────────────┐                    │
│  │  WebSocketClient (network/WebSocketClient.ts)   │                    │
│  │  - JSON 패킷 송수신 · type → handler 라우팅     │                    │
│  └─────────────────────────────────────────────────┘                    │
└──────────────────────┬──────────────────────────────────────────────────┘
                       │  ws://host:port  (text frames, JSON)
                       ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                           서버 (JVM 21, 순수 Java)                       │
│                                                                          │
│  ┌──────────────┐  ┌─────────────────────┐  ┌──────────────────────┐   │
│  │ WebSocket    │→ │ PacketDispatcher    │→ │ Domain Handlers      │   │
│  │ GameServer   │  │ (type → Handler 등록)│  │ Inventory/Combat/Chat│   │
│  └──────────────┘  └─────────────────────┘  └──────────────────────┘   │
│         │                  │                         │                  │
│         │                  ▼                         ▼                  │
│         │          ┌──────────────┐         ┌──────────────────┐       │
│         │          │ AuthSessions │         │ World            │       │
│         │          │ (LOGIN/REG)  │         │ ├ Channel/Map    │       │
│         │          └──────┬───────┘         │ ├ Players        │       │
│         │                 │                 │ ├ Monsters/AI    │       │
│         │                 ▼                 │ └ DroppedItems   │       │
│         │          ┌──────────────┐         └─────┬────────────┘       │
│         │          │ AuthService  │               │                    │
│         │          │ (BCrypt-like)│               │                    │
│         │          └──────┬───────┘               │                    │
│         │                 │                       │                    │
│         │                 ▼                       ▼                    │
│         │          ┌─────────────────────────────────────────┐         │
│         │          │ JDBC Repositories (Account/Player)      │         │
│         │          │           ↓                             │         │
│         │          │       H2 (file: ~/mygame-data/mygame)   │         │
│         └──────────┴─────────────────────────────────────────┘         │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────┐        │
│  │ GameLoop (ScheduledExecutorService, 30 tick/s)             │        │
│  │  - 몬스터 AI tick · 드롭 TTL 청소 · 위치 브로드캐스트 샘플 │        │
│  └────────────────────────────────────────────────────────────┘        │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────┐        │
│  │ EventBus (subscribe/publish)                                │        │
│  │  ExpGained / LeveledUp → ProgressionSystem · GameServer     │        │
│  └────────────────────────────────────────────────────────────┘        │
└──────────────────────────────────────────────────────────────────────────┘
```

## 2. 디렉터리 구조

```
MyGame/
├── CLAUDE.md                        프로젝트 전제·로드맵
├── README.md                        간단 시작 가이드
├── docs/                            ★ 본 문서 디렉터리
├── client/                          Phaser 3 + TypeScript + Vite
│   ├── index.html                   HUD DOM · CSS 전역
│   ├── public/assets/*.json         Tiled 맵 export (henesys/ellinia)
│   └── src/
│       ├── main.ts                  Phaser 부트스트랩
│       ├── auth/LoginScreen.ts      로그인/회원가입 DOM 화면
│       ├── network/WebSocketClient.ts
│       ├── scenes/
│       │   ├── GameScene.ts         메인 게임 씬 (네트워크 핸들러 + 입력 + 렌더 오케스트레이션)
│       │   ├── MapController.ts     타일맵 · 포털 관리
│       │   └── TextureFactory.ts    런타임 텍스처 생성
│       ├── entities/                RemotePlayer · MonsterSprite · DroppedItemSprite
│       ├── ui/                      DOM 기반 HUD 모듈 모음
│       │   ├── HudView.ts           HP/MP/EXP/스킬바/인벤토리 DOM 갱신
│       │   ├── ChatController.ts    채팅 입력·로그·ghost 노출
│       │   ├── EffectFactory.ts     데미지/공격/스킬/EXP/레벨업 이펙트
│       │   └── PickupLog.ts         좌하단 획득 알림
│       └── data/                    ItemMeta · InventoryOrder
└── server/                          순수 Java (Gradle)
    └── src/main/java/mygame/
        ├── Main.java                서버 부트스트랩
        ├── auth/                    AuthService · PasswordHasher
        ├── db/                      Database · Repository (JDBC)
        ├── network/
        │   ├── GameServer.java      WebSocket 진입점 + 세션 라이프사이클
        │   ├── PacketDispatcher.java
        │   ├── PacketEnvelope.java  type 래핑 직렬화 helper
        │   ├── PacketContext.java   핸들러 호출 컨텍스트
        │   ├── PacketHandler.java
        │   ├── AuthSessions.java    LOGIN/REGISTER 라우팅
        │   ├── ChatHandler.java     ★ 분리 핸들러
        │   ├── InventoryHandler.java★ 분리 핸들러
        │   ├── CombatHandler.java   ★ 분리 핸들러
        │   ├── PlayerCombatHandler.java  몬스터→플레이어 피격 콜백
        │   ├── SessionNotifier.java 공통 송신(STATS/EQUIPMENT)
        │   └── packets/Packets.java 모든 DTO record
        └── game/
            ├── World.java           채널 · 맵 · 전역 시퀀스
            ├── GameMap.java         단일 맵 (플레이어/몬스터/드롭)
            ├── GameLoop.java        Scheduled tick
            ├── SpawnPoint.java      포털·스폰 좌표
            ├── CombatService.java   몬스터 데미지·드롭·EXP 분배
            ├── CombatListener.java  콜백 인터페이스
            ├── ProgressionSystem.java EXP 정책(이벤트 핸들러)
            ├── entity/              Player · Monster · MonsterTemplate · MonsterRegistry
            ├── ai/                  MonsterState (Idle/Wander/Chase)
            ├── event/               EventBus · GameEvent (sealed)
            ├── item/                Inventory · Equipment · DroppedItem · ItemTemplate · DropTable · ItemRegistry · EquipSlot
            ├── skill/               Skill (sealed) · PowerStrike · TripleBlow · Recovery · SkillContext · SkillOutcome · SkillRegistry · AttackBox
            └── stat/                Stats · BaseStats · EquipmentStatDecorator · StatProvider
```

## 3. 데이터 흐름 — 한 라운드

플레이어가 키 입력 → 서버 검증 → 모든 클라이언트에 반영되기까지의 흐름:

```
[1] 클라 GameScene.update()         이동 키 감지 → MOVE 패킷 송신 (10Hz)
[2] WebSocketClient                 JSON 직렬화 후 ws.send
[3] GameServer.onMessage            sessionPlayers 에서 Player 조회
[4] PacketDispatcher.dispatch       envelope.type → 등록 핸들러 호출
[5] handleMove                      Player.updatePosition + map.broadcastExcept
[6] 다른 클라가 PLAYER_MOVE 수신    → RemotePlayer.setTarget (보간 이동)
```

전투(스킬) 의 경우 EventBus 가 끼어든다:

```
USE_SKILL 패킷
  → CombatHandler.handleUseSkill
     → Player.tryActivateSkill (compute, 원자적)
     → Skill.apply(SkillContext)
        → CombatService.damageMonster → Monster.applyDamage
           → 사망 시 EventBus.publish(ExpGained)
              → ProgressionSystem 이 EXP 누적 + LeveledUp 발행
                 → GameServer.broadcastProgression 이 PLAYER_EXP/PLAYER_LEVELUP 송신
```

## 4. 동시성 모델

서버는 **여러 WebSocket 스레드** 가 동시에 들어올 수 있는 환경이다 ([Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) 의 worker pool).

| 공유 자원 | 보호 방식 |
|---|---|
| `sessionPlayers` (WebSocket → Player) | `ConcurrentHashMap` |
| `Player` 의 위치/스탯 필드 | `volatile` 단일 갱신, HP/MP/EXP/메소는 `synchronized` |
| `Player.skillLastUsedAt` | `ConcurrentHashMap` + `compute()` 원자 연산 |
| `GameMap` 의 플레이어/몬스터/드롭 | `ConcurrentHashMap` (아이디 → 객체) |
| `World.itemIdSeq` / `playerIdSeq` | `AtomicInteger`/`AtomicLong` |
| EventBus | `CopyOnWriteArrayList` 구독자 |

**원자성 강화 사례** ([상세](./09-development-history.md#리팩토링-2026-04-24-25)):
- `Player.gainExp()` 는 exp 누적 + level 증가가 원자적이어야 해 `synchronized`
- `Player.tryActivateSkill()` 은 "이전 사용 시각 조회 → 비교 → 갱신" 을 단일 `compute()` 람다로 묶어 두 스레드가 동시에 통과하는 레이스를 차단

## 5. 게임 루프

`GameLoop` 는 `ScheduledExecutorService` 한 스레드에서 30 tick/s 로 동작:

- 모든 맵의 몬스터 AI 갱신
- 드롭 아이템 TTL 검사 → 만료 시 제거 + `ITEM_REMOVED` 브로드캐스트
- 몬스터 위치 변동 시 `MONSTER_MOVE` 송출

게임 루프는 **권위(authoritative) 위치 계산을 하지 않는다**. 플레이어 좌표는 클라이언트 보고를 받아 그대로 저장한다 — 학습 단계에서는 의도적으로 단순화. Phase 후반에 검증/재계산을 추가할 자리가 마련되어 있다.

## 6. 빌드 · 실행

```bash
# 서버 (Gradle, Java 21)
cd server
./gradlew run                  # 메인 클래스 실행
./gradlew compileJava          # 컴파일만

# 클라이언트 (Vite + TypeScript)
cd client
npm install
npm run dev                    # 핫리로드 개발 서버
./node_modules/.bin/tsc --noEmit   # 타입체크
./node_modules/.bin/vite build     # 정적 빌드
```

서버는 `~/mygame-data/mygame.mv.db` 에 H2 데이터를 저장한다(파일 기반, 재시작 후 영속).

## 7. 다음 문서

- 패킷 송수신의 구체적 설계는 [02-server-network.md](./02-server-network.md)
- 도메인 객체 설계는 [03-server-game-domain.md](./03-server-game-domain.md)
- 클라 Scene 분할은 [05-client-architecture.md](./05-client-architecture.md)
