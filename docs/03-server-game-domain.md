# 03 · 서버 게임 도메인

`server/src/main/java/mygame/game/` 패키지. 게임 규칙·상태·AI 가 모이는 곳이며 네트워크와는 격리된다.

## 1. 계층 구조

```
World ── Channel(미사용, 향후 분리) ── GameMap
                                       ├─ Players (ConcurrentHashMap<int, Player>)
                                       ├─ Monsters (ConcurrentHashMap<int, Monster>)
                                       └─ DroppedItems (ConcurrentHashMap<int, DroppedItem>)
```

`World` 는 모든 맵을 보유하고, 전역 시퀀스(`itemIdSeq`, 향후 채널 ID 등)를 발행한다.
맵 별 브로드캐스트는 항상 `GameMap.broadcast` 또는 `broadcastExcept` 로 이뤄지며, 다른 맵 사용자에게는 절대 새지 않는다.

## 2. Player

[`Player.java`](../server/src/main/java/mygame/game/entity/Player.java) 는 게임 로직의 중심.

### 2.1 가변 상태

| 필드 | 보호 | 설명 |
|---|---|---|
| `mapId, x, y, vx, vy, facing` | `volatile` | 한 필드 단위 갱신 |
| `level, exp` | `volatile` + `synchronized gainExp` | 레벨업이 두 필드를 함께 변경하므로 일관성 보장 |
| `hp, mp` | `synchronized` 메서드(`takeDamage/restoreHp/...`) | 차감/회복이 비교+갱신 |
| `meso` | `synchronized addMeso/spendMeso` | 음수 방지 |
| `invulnerableUntil` | `volatile` | iframe 종료 시각 |
| `skillLastUsedAt` | `ConcurrentHashMap` + `compute` | 쿨다운 체크 원자성 |
| `inventory, equipment` | 자체 동기화 | 별도 객체에 위임 |

### 2.2 핵심 메서드

```java
public synchronized int takeDamage(int amount, long now, long iframeMs) {
    if (amount <= 0 || hp <= 0) return 0;
    if (now < invulnerableUntil) return 0;          // iframe
    int applied = Math.min(hp, amount);
    hp -= applied;
    invulnerableUntil = now + iframeMs;
    return applied;                                  // 0 이면 무효 — 호출자가 무시
}

public boolean tryActivateSkill(String skillId, long cooldownMs, long now) {
    boolean[] activated = {false};
    skillLastUsedAt.compute(skillId, (k, last) -> {
        if (last != null && now - last < cooldownMs) return last;   // 쿨다운 중
        activated[0] = true;
        return now;
    });
    return activated[0];
}

public synchronized int gainExp(int amount) {
    if (amount <= 0) return 0;
    int levelUps = 0;
    exp += amount;
    while (exp >= expToNextLevel()) {
        exp -= expToNextLevel();
        level++;
        levelUps++;
    }
    return levelUps;
}
```

`expToNextLevel()` 은 `50 * level` (단순 선형). 학습 단계라 의도적으로 단순.

### 2.3 effectiveStats

```java
public Stats effectiveStats() {
    return equipment.decorate(new BaseStats(level)).stats();
}
```

`Equipment.decorate(StatProvider)` 는 장착 슬롯 마다 `EquipmentStatDecorator` 를 체인으로 감싼다 — Decorator 패턴의 직접 적용. 자세한 내용은 [08-design-patterns.md](./08-design-patterns.md#decorator).

## 3. Monster · AI

[`Monster.java`](../server/src/main/java/mygame/game/entity/Monster.java) 는 `MonsterTemplate`(불변 정의) 과 인스턴스 상태를 분리한다.

### 3.1 State 패턴

`MonsterState` 는 `sealed interface` 가 아니라 일반 추상 클래스로 시작:

```java
public abstract class MonsterState {
    public abstract MonsterState tick(Monster m, GameMap map, long now);
}
public final class IdleState extends MonsterState   { ... }
public final class WanderState extends MonsterState { ... }
public final class ChaseState extends MonsterState  { ... }
```

각 tick 의 반환값이 다음 상태이다. 전이 규칙:

```
Idle  ──(주변 플레이어 감지)──▶ Chase
Idle  ──(N초 경과)──────────▶ Wander
Wander──(목적지 도달)────────▶ Idle
Wander──(주변 플레이어 감지)─▶ Chase
Chase ──(범위 이탈/대상 사망)─▶ Idle
```

### 3.2 GameLoop 와의 결합

`GameLoop.tick()` 은 모든 맵의 모든 몬스터에 `state = state.tick(m, map, now)` 를 호출한다.
상태 객체 자체는 stateless 라 안전하게 공유된다(`IdleState.INSTANCE` 등).

## 4. CombatService

[`CombatService.java`](../server/src/main/java/mygame/game/CombatService.java) 가 데미지 적용·드롭·EXP 분배의 단일 통로.

| 메서드 | 책임 |
|---|---|
| `damageMonster(map, attacker, monster, dmg)` | HP 차감 + `MONSTER_DAMAGED` 브로드캐스트 + 사망 시 `finishKill` |
| `finishKill(map, attacker, monster)` | 드롭 테이블 굴리기 + `ITEM_DROPPED` 브로드캐스트 + EventBus 로 `ExpGained` 발행 + `MONSTER_DIED` 송출 |

스킬 흐름은 `Skill.apply` 안에서 `Monster.applyDamage` 를 직접 호출하므로,
`CombatHandler` 가 hit 결과를 받아 `damageMonster` 가 아닌 `finishKill` 만 호출하는 점이 차이.

## 5. EventBus + ProgressionSystem

이벤트 기반 분리는 EXP·레벨업 처리에 적용:

```java
// CombatService 가 이벤트 발행
eventBus.publish(new ExpGained(player, gained));

// ProgressionSystem 이 도메인 변환
public ProgressionSystem(EventBus bus) {
    bus.subscribe(this::on);
}
private void on(GameEvent e) {
    if (e instanceof ExpGained(Player p, int g)) {
        int ups = p.gainExp(g);
        if (ups > 0) bus.publish(new LeveledUp(p, p.level()));
    }
}

// GameServer 가 네트워크 전환
eventBus.subscribe(this::broadcastProgression);
```

도메인(EXP 누적) → 네트워크(패킷 송출) 로 책임이 갈라져, 도메인 변경이 네트워크 코드를 건드리지 않는다.

## 6. Inventory · Equipment · Item

```
ItemTemplate (불변 정의: id, name, color, type, [bonusStats], [useEffect])
   │
   ▼
ItemRegistry (id → ItemTemplate)        ← Factory 패턴 변종
   │
   ├─ Inventory.add/remove                (Map<id, count>, 24슬롯 캡)
   ├─ Equipment.equip/unequip             (EnumMap<EquipSlot, id>)
   └─ DroppedItem (id, templateId, x, y, expiresAt, amount)
        └─ DropTable (몬스터 → 가능한 드롭 + 확률)
```

### 6.1 장비 ID 의 SSoT

`ItemRegistry.equipmentIds()` 가 EQUIPMENT 타입 아이템 ID 만 추려 반환하고,
서버 `META` 패킷이 이 목록을 클라에 전달한다. 클라 `EQUIPMENT_IDS` 는 비어 있다가 `applyMeta` 시 채워진다 — 서버↔클라 하드코딩 동기 유지가 사라졌다.

## 7. Skill

[`Skill.java`](../server/src/main/java/mygame/game/skill/Skill.java) 는 `sealed interface`:

```java
public sealed interface Skill permits PowerStrike, TripleBlow, Recovery {
    String JOB_BEGINNER = "BEGINNER";
    String id(); String name(); String job();
    int mpCost(); long cooldownMs();
    void apply(SkillContext ctx);
}
```

각 스킬은 `INSTANCE` 싱글턴이고 상태가 없다 — 플레이어 별 쿨다운/MP 는 `Player` 에 보관.

### 7.1 SkillContext / SkillOutcome

```java
record SkillContext(Player caster, GameMap map, String dir, long now, SkillOutcome outcome) {
    static SkillContext of(...) { ... }
}
record SkillOutcome(List<Hit> hits) {
    record Hit(int monsterId, int damage, int remainingHp, boolean killed) {}
}
```

스킬 구현체는 `ctx.outcome().hits().add(...)` 로 결과를 적재하기만 하고, 데미지 패킷 송출은 호출자(`CombatHandler`)가 일괄 처리. 책임이 깔끔히 갈린다.

### 7.2 AttackBox

직사각형 충돌 박스 계산만 담당하는 정적 유틸:

```java
public static List<Monster> nearestInFront(Player p, GameMap map, String dir, double rangeFactor, int max);
public static List<Monster> inFront(Player p, GameMap map, String dir, double rangeFactor);
```

`rangeFactor` 가 1.0 이면 기본 범위, 스킬마다 1.5/2.0 으로 확장.

## 8. Stat 시스템 — Decorator 체인

```java
public sealed interface StatProvider permits BaseStats, EquipmentStatDecorator {
    Stats stats();
}
public record BaseStats(int level) implements StatProvider {
    public Stats stats() { return new Stats(50 + level*10, 30 + level*5, 5+level, 100); }
}
public record EquipmentStatDecorator(StatProvider inner, Stats bonus) implements StatProvider {
    public Stats stats() { return inner.stats().plus(bonus); }
}
```

`Equipment.decorate(BaseStats)` 가 슬롯마다 `new EquipmentStatDecorator(prev, slotBonus)` 로 감싸 체인을 만든다. 따라서 `effectiveStats()` 는 한 줄:

```java
return equipment.decorate(new BaseStats(level)).stats();
```

장비 종류가 늘어도 Decorator 체인 길이만 늘 뿐 분기가 없다.

## 9. PlayerCombatHandler — 몬스터→플레이어 피해

```java
world.setCombatListener(new PlayerCombatHandler(world, notifier::sendStats));
```

몬스터 AI(`ChaseState` 등) 가 플레이어와 닿았을 때 `world.combatListener().onMonsterTouchPlayer(...)` 호출. `PlayerCombatHandler` 가 다음을 수행:

1. `Player.takeDamage(dmg, now, IFRAME_MS)` — iframe 처리
2. 적용된 데미지가 0 이면 무시
3. `PLAYER_DAMAGED` 브로드캐스트
4. HP=0 이면 `PLAYER_DIED` 후 일정 시간 뒤 스폰 지점으로 `PLAYER_RESPAWN`
5. `notifier.sendStats(player)` 로 본인 HP/MP 동기화

## 10. 다음 문서

- 영속성 · 인증 코드: [04-server-persistence.md](./04-server-persistence.md)
- 디자인 패턴 정리: [08-design-patterns.md](./08-design-patterns.md)
