# 07 · 패킷 프로토콜 명세

서버는 [`Packets.java`](../server/src/main/java/mygame/network/packets/Packets.java) 의 record 들을, 클라는
`as` 캐스팅 기반의 동적 타입을 사용한다. 모든 메시지는 단일 JSON 텍스트 프레임이며 최상위 `type` 으로 분기한다.

> 표 안의 화살표:
> - **C → S** : 클라이언트가 서버에 송신
> - **S → C** : 서버가 클라이언트에 송신 (개별/브로드캐스트 모두 포함)

## 1. 인증 · 세션

| 방향 | type | 필드 | 비고 |
|---|---|---|---|
| C → S | `LOGIN` | `username`, `password` | 성공 시 `AUTH` 응답 |
| C → S | `REGISTER` | `username`, `password` | 동일 |
| S → C | `AUTH` | `success`, `error?`, `accountId?`, `username?` | LOGIN/REGISTER 공통 응답 |
| C → S | `JOIN` | `name?` | 신규 캐릭터 생성 시 사용. 인증 선행 필수 |
| S → C | `WELCOME` | `playerId`, `self`, `others[]`, `monsters[]`, `items[]` | JOIN 직후 본인에게만 |
| S → C | `META` | `equipmentIds[]`, `skills[]` | JOIN 직후. 정적 게임 메타(SSoT) |

### `META` 의 `skills` 항목

```ts
interface SkillMetaEntry { id: string; name: string; mpCost: number; cooldownMs: number; }
```

## 2. 이동 · 맵 전환

| 방향 | type | 필드 | 비고 |
|---|---|---|---|
| C → S | `MOVE` | `x`, `y`, `vx`, `vy` | 10Hz |
| S → C | `PLAYER_MOVE` | `id`, `x`, `y`, `vx`, `vy` | 본인 제외 브로드캐스트 |
| S → C | `PLAYER_JOIN` | `player: PlayerState` | 같은 맵 다른 사용자 |
| S → C | `PLAYER_LEAVE` | `id` | |
| C → S | `CHANGE_MAP` | `targetMap`, `targetX`, `targetY` | 포털 진입 |
| S → C | `MAP_CHANGED` | `mapId`, `x`, `y`, `others[]`, `monsters[]`, `items[]` | 본인에게만 |

`PlayerState` = `{ id, name, x, y }`.

## 3. 몬스터

| 방향 | type | 필드 |
|---|---|---|
| S → C | `MONSTER_SPAWN` | `monster: MonsterState` |
| S → C | `MONSTER_MOVE` | `id`, `x`, `vx` |
| S → C | `MONSTER_DAMAGED` | `id`, `dmg`, `hp`, `attackerId` |
| S → C | `MONSTER_DIED` | `id` |

`MonsterState` = `{ id, template, x, y, hp, maxHp }`.

## 4. 전투 · 스킬

| 방향 | type | 필드 |
|---|---|---|
| C → S | `ATTACK` | `dir` |
| C → S | `USE_SKILL` | `skillId`, `dir` |
| S → C | `SKILL_USED` | `playerId`, `skillId`, `dir` (이펙트용 브로드캐스트) |
| S → C | `PLAYER_DAMAGED` | `playerId`, `dmg`, `currentHp`, `maxHp`, `attackerId` (음수 = 몬스터 -id) |
| S → C | `PLAYER_DIED` | `playerId` |
| S → C | `PLAYER_RESPAWN` | `playerId`, `mapId`, `x`, `y`, `hp` |

## 5. 진행도 (EXP/레벨)

| 방향 | type | 필드 |
|---|---|---|
| S → C | `PLAYER_EXP` | `exp`, `level`, `toNextLevel`, `gained` (본인에게만) |
| S → C | `PLAYER_LEVELUP` | `playerId`, `level` (브로드캐스트, 이펙트용) |

## 6. 인벤토리 · 아이템 · 메소

| 방향 | type | 필드 | 비고 |
|---|---|---|---|
| C → S | `PICKUP` | (없음) | 자동 픽업: 클라가 가까운 아이템 인지 시 호출 |
| C → S | `USE_ITEM` | `templateId` | 소비 아이템 1개 사용 |
| C → S | `DROP_ITEM` | `templateId`, `amount` | 인벤에서 월드로 |
| S → C | `INVENTORY` | `items: { [id: string]: number }` | 풀 싱크 |
| S → C | `ITEM_DROPPED` | `item: DroppedItemState` | 맵 위 새 아이템 |
| S → C | `ITEM_REMOVED` | `id` | 픽업·만료 |
| S → C | `MESO` | `meso`, `gained` | gained: 변화량(+획득/-소비) |

`DroppedItemState` = `{ id, templateId, x, y }`.

> 아이템 획득 알림은 `INVENTORY` 풀싱크의 클라 측 diff 로 도출된다 — 별도 `ITEM_GAINED` 패킷은 두지 않는다 ([06-client-ui.md](./06-client-ui.md#62-호출-시점)).

## 7. 장비 · 스탯

| 방향 | type | 필드 |
|---|---|---|
| C → S | `EQUIP` | `templateId` |
| C → S | `UNEQUIP` | `slot` (`"WEAPON"` 등 EquipSlot enum 이름) |
| S → C | `EQUIPMENT` | `playerId`, `slots: { [slot]: templateId }` |
| S → C | `STATS` | `maxHp`, `maxMp`, `attack`, `speed`, `currentHp`, `currentMp` (본인에게만) |

장비 변화는 항상 `EQUIPMENT` 와 `STATS` 가 같이 송신되도록 `SessionNotifier.sendEquipmentAndStats` 가 일괄 처리한다.

## 8. 채팅

| 방향 | type | 필드 | 비고 |
|---|---|---|---|
| C → S | `CHAT` | `scope`, `target?`, `message` | scope ∈ {`ALL`, `WHISPER`} |
| S → C | `CHAT` | `scope`, `sender`, `message` | scope 가 `WHISPER:<name>` 면 귓속말 echo |

서버는 메시지를 200자에서 자른다.

## 9. 오류

| 방향 | type | 필드 |
|---|---|---|
| S → C | `ERROR` | `message` |

핸들러에서 비즈니스 검증이 실패하면 즉시 `ERROR` 응답하고 서버 상태는 변경하지 않는다. 클라는 채팅 sys 라인으로 표시.

## 10. 직렬화

서버 송신:

```java
String payload = PacketEnvelope.wrap(json, "STATS", new StatsPacket(...));
conn.send(payload);
```

내부적으로 `{"type":"STATS", ...record fields}` 형태로 직렬화된다.

서버 수신:

```java
JsonNode envelope = json.readTree(message);
String type = envelope.get("type").asText();
JsonNode body = envelope;                              // record 필드는 평탄
PacketHandler handler = handlers.get(type);
handler.handle(new PacketContext(json, conn, player, body));
```

핸들러 안에서 `ctx.json().treeToValue(ctx.body(), MoveRequest.class)` 같은 식으로 record 로 디코딩.

## 11. 변경 가이드

새 패킷 추가 시:

1. `Packets.java` 에 record 추가 (방향에 맞춰 C→S/S→C 구역에 배치)
2. **C → S** 면 `GameServer.registerHandlers()` 에 dispatcher.register
3. **S → C** 면 송신 측에서 `PacketEnvelope.wrap(json, "TYPE", record)` 호출
4. 클라 `GameScene.setupNetwork()` 에 `network.on('TYPE', handler)` 추가
5. 본 문서의 표에 추가

## 12. 다음 문서

- 패턴 학습 노트: [08-design-patterns.md](./08-design-patterns.md)
- 개발 이력: [09-development-history.md](./09-development-history.md)
