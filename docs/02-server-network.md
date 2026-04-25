# 02 · 서버 네트워크 계층

`server/src/main/java/mygame/network/` 패키지 안에 있는 코드를 다룬다. WebSocket 진입점,
패킷 디스패처, 도메인별 핸들러로 분리된 구조와 그 이유를 설명한다.

## 1. 패킷 포맷

모든 메시지는 **단일 JSON 텍스트 프레임** 이며, 최상위에 `type` 문자열을 둔다.

```json
{ "type": "MOVE", "x": 120.5, "y": 200, "vx": 200, "vy": 0 }
```

송신 측은 [`PacketEnvelope.wrap`](../server/src/main/java/mygame/network/PacketEnvelope.java) 로 payload 를 감싸 직렬화하고, 수신 측은 동일한 클래스의 헬퍼로 `type` 을 추출해 분기한다. payload 는 `Packets` 의 record 들이다 — Jackson 이 record 를 네이티브 지원하므로 별도 매퍼 설정이 없다.

## 2. 진입점 — `GameServer`

`GameServer extends WebSocketServer` 가 `Java-WebSocket` 라이브러리의 진입점이다.

| 메서드 | 책임 |
|---|---|
| `onOpen` | 로그만, 아직 인증 전이라 세션 등록 X |
| `onMessage` | `sessionPlayers.get(conn)` 로 플레이어 조회 후 `dispatcher.dispatch()` |
| `onClose` | 인증 정리(`auth.clear`), 맵에서 제거+브로드캐스트, DB 저장 |
| `onError` | 세션 단위 로그 |
| `onStart` | `setConnectionLostTimeout(60)` + `gameLoop.start()` |

핸들러는 다음과 같이 등록된다:

```java
private void registerHandlers() {
    dispatcher.register("LOGIN", auth::handleLogin);
    dispatcher.register("REGISTER", auth::handleRegister);
    dispatcher.register("JOIN", this::handleJoin);
    dispatcher.register("MOVE", this::handleMove);
    dispatcher.register("CHANGE_MAP", this::handleChangeMap);
    dispatcher.register("ATTACK", combatHandler::handleAttack);
    dispatcher.register("USE_SKILL", combatHandler::handleUseSkill);
    dispatcher.register("PICKUP", inventoryHandler::handlePickup);
    dispatcher.register("EQUIP", inventoryHandler::handleEquip);
    dispatcher.register("UNEQUIP", inventoryHandler::handleUnequip);
    dispatcher.register("USE_ITEM", inventoryHandler::handleUseItem);
    dispatcher.register("DROP_ITEM", inventoryHandler::handleDropItem);
    dispatcher.register("CHAT", chatHandler::handle);
    eventBus.subscribe(this::broadcastProgression);
}
```

`GameServer` 본체는 **세션 라이프사이클(JOIN/LEAVE/MOVE/CHANGE_MAP) + EventBus 구독** 만 책임진다.
세부 명령 핸들러는 모두 분리 클래스로 위임 — 619줄에서 359줄로 축소된 결과 ([09-development-history.md](./09-development-history.md)).

## 3. PacketDispatcher

```java
public final class PacketDispatcher {
    private final Map<String, PacketHandler> handlers = new ConcurrentHashMap<>();

    public void register(String type, PacketHandler handler) { handlers.put(type, handler); }

    public void dispatch(WebSocket conn, Player player, String message) { ... }
}
```

`PacketHandler` 는 `(PacketContext) -> void` 의 함수 인터페이스이고, `PacketContext` 는
`(json, conn, player, body)` 의 묶음이다. 핸들러는 이 컨텍스트만 의존하며 `GameServer` 자체를
참조하지 않아 단위 테스트가 용이하다.

알 수 없는 `type` 은 `PacketEnvelope.error("unknown type")` 로 응답한다.

## 4. 도메인 핸들러 분리

리팩토링으로 핸들러가 4개 파일로 나뉘었다.

### 4.1 `ChatHandler`

- `ALL`: 같은 맵 브로드캐스트
- `WHISPER`: `World.playerByName()` 으로 대상 조회 후 송신자/수신자 양쪽에 동일 페이로드 echo
- `MAX_CHAT_LEN = 200` 으로 잘라 안전 처리

### 4.2 `InventoryHandler`

5개 핸들러: `PICKUP / EQUIP / UNEQUIP / USE_ITEM / DROP_ITEM`. 공통 패턴:

```
1) Player 상태 변경 (Inventory · Equipment)
2) ctx.conn().send(InventoryPacket)        ← 본인에게 인벤 풀 싱크
3) notifier.sendStats / sendEquipmentAndStats  (스탯에 영향이 있을 때)
```

`DROP_TTL_MS = 60_000` 상수가 한 곳에 모여 있어 리터럴 중복이 없다.

### 4.3 `CombatHandler`

| 핸들러 | 흐름 |
|---|---|
| `handleAttack` | `AttackBox.nearestInFront` 로 가장 가까운 1마리만 타격 (기본 공격) |
| `handleUseSkill` | 직업 태그 검증 → `tryActivateSkill` (원자) → MP 차감 → `skill.apply(ctx)` → `MONSTER_DAMAGED` 브로드캐스트 → 사망 시 `CombatService.finishKill` |

스킬 효과는 [`Skill.apply`](../server/src/main/java/mygame/game/skill/Skill.java) 의 책임이며,
핸들러는 외부 검증과 결과 송출만 담당한다.

### 4.4 `SessionNotifier`

도메인 핸들러들이 공통으로 호출하는 송신 헬퍼.

```java
public void sendStats(Player player) { ... }            // STATS 패킷
public void sendEquipmentAndStats(Player player) { ... }// EQUIPMENT + STATS
public static Map<String,String> equipmentSnapshotAsStringMap(Player p) { ... }
```

서버↔클라 간 "한 행위가 끝나면 본인에게 보내야 하는 두 패킷" 의 중복을 제거하고,
`PlayerCombatHandler` (몬스터→플레이어 피격 콜백) 에서도 같은 인스턴스를 재사용한다.

## 5. 인증 흐름 — `AuthSessions`

```
LOGIN/REGISTER 요청 도착
  → AuthSessions.handleLogin(ctx)
     → AuthService.login(username, password)
        → AccountRepository.findByUsername
        → PasswordHasher.verify (PBKDF2)
     → 성공 시: authedConnections.put(conn, accountId)
              ctx.conn().send(AuthResponse(success=true, accountId))
     → 실패 시: AuthResponse(success=false, error="...")
JOIN 요청 도착
  → auth.accountIdOf(conn) 으로 인증 여부 확인 (없으면 거부)
  → 캐릭터 복원/생성 후 sessionPlayers 에 등록
```

`AuthSessions` 는 인증 자체는 `AuthService` 에 위임하고, "이 WebSocket 연결이 어떤 계정으로 인증됐는가" 만 메모리에 보관한다. `onClose` 시 `auth.clear(conn)` 으로 정리.

## 6. JOIN 의 책임

`handleJoin` 은 다음 작업을 직렬로 수행:

1. 인증 확인
2. 동일 연결 중복 JOIN 거부
3. **계정 1:1 캐릭터** — 있으면 복원, 없으면 생성 (`players.name UNIQUE` 위반 시 거부)
4. `Player` 인스턴스 생성 + DB 데이터 복원 (`level/exp/meso/items/equipment`)
5. HP/MP 풀 충전 (HP/MP 영속화는 후속 Phase 과제)
6. `sessionPlayers` · `World.players` · `GameMap.players` 에 등록
7. 본인에게 `WELCOME` + `META` + `PLAYER_EXP` + `INVENTORY` + `MESO` + `EQUIPMENT` + `STATS` 송출
8. 같은 맵 다른 사용자에게 `PLAYER_JOIN` 브로드캐스트

`META` 패킷은 [05-client-architecture.md](./05-client-architecture.md#서버-메타-수신) 에서 자세히 다룬다 — 서버 레지스트리를 단일 진실 원천으로 두는 설계.

## 7. 패킷 일생 — 한 사례 (USE_SKILL)

```
client: GameScene.handleSkills()
        → network.send({ type:'USE_SKILL', skillId:'power_strike', dir:'right' })
                                                                        │
ws frame ─────────────────────────────────────────────────────────────  │
                                                                        ▼
server: GameServer.onMessage
        → dispatcher.dispatch(conn, player, message)
        → PacketEnvelope.unwrap (type 추출)
        → CombatHandler.handleUseSkill(ctx)
            ├─ SkillRegistry.exists / .get
            ├─ Player.tryActivateSkill (compute 원자 연산)
            ├─ Player.spendMp
            ├─ skill.apply(SkillContext) ← 데미지/치유는 여기서
            ├─ map.broadcast("SKILL_USED", ...)             → 전 클라
            ├─ 각 hit 마다 broadcast("MONSTER_DAMAGED", ...)  → 전 클라
            ├─ 사망 시 combatService.finishKill              → 드롭/EXP 이벤트
            └─ notifier.sendStats(player)                    → 본인 STATS
```

## 8. 회복 동작(에러)

핸들러에서 검증이 실패하면 `PacketEnvelope.error(json, "사유")` 로 즉시 응답하고
서버 상태는 변경하지 않는다. 클라는 `ERROR` 타입으로 받아 채팅창 sys 로 표시:

```ts
this.network.on('ERROR', (p) => {
  const msg = String(p.message ?? '');
  this.chat.append('sys', `[오류] ${msg}`);
  this.lastServerError = msg;     // close 직후 alert 사유 보여주기
});
```

## 9. 다음 문서

- 도메인 객체(World/Map/Player/...): [03-server-game-domain.md](./03-server-game-domain.md)
- 인증·DB 상세: [04-server-persistence.md](./04-server-persistence.md)
- 패킷 풀 명세: [07-protocol.md](./07-protocol.md)
