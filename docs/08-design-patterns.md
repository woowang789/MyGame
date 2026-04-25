# 08 · 디자인 패턴 학습 노트

본 프로젝트에서 의도적으로 적용한 패턴과 그 적용처. "왜 이 패턴이 필요한가" 와 "다른 선택지" 를 함께 적었다.

## 1. Command 패턴 — Skill

### 적용 코드

[`Skill.java`](../server/src/main/java/mygame/game/skill/Skill.java)

```java
public sealed interface Skill permits PowerStrike, TripleBlow, Recovery {
    void apply(SkillContext ctx);
}
public final class PowerStrike implements Skill {
    public static final PowerStrike INSTANCE = new PowerStrike();
    public void apply(SkillContext ctx) { /* 단일 타격 + 1.5x 데미지 */ }
}
```

### 왜?

호출자(`CombatHandler`) 는 `skill.apply(ctx)` 한 줄만 호출하면 된다. 분기 없음.

```java
// 분기 코드 — 패턴 도입 전이라면
switch (skillId) {
    case "power_strike": doPowerStrike(...); break;
    case "triple_blow":  doTripleBlow(...);  break;
    case "recovery":     doRecovery(...);    break;
}

// Command 패턴 후
SkillRegistry.get(skillId).apply(ctx);
```

신규 스킬 추가 시 `Skill.permits` 와 `SkillRegistry.ALL_SKILLS` 만 갱신.

## 2. State 패턴 — 몬스터 AI

[`MonsterState`](../server/src/main/java/mygame/game/ai/MonsterState.java) + Idle/Wander/Chase.

```java
public abstract class MonsterState {
    public abstract MonsterState tick(Monster m, GameMap map, long now);
}
```

각 tick 의 반환값이 *다음* 상태. 전이 그래프가 클래스 사이로 분산되어 한 클래스가 너무 커지지 않는다. Spring 같은 프레임워크 도움 없이 순수 자바로 표현 — 학습 의도에 정확히 부합.

## 3. Observer 패턴 — EventBus

[`EventBus`](../server/src/main/java/mygame/game/event/EventBus.java) 는 가장 단순한 형태:

```java
public final class EventBus {
    private final List<Consumer<GameEvent>> subscribers = new CopyOnWriteArrayList<>();
    public void subscribe(Consumer<GameEvent> c) { subscribers.add(c); }
    public void publish(GameEvent e) { subscribers.forEach(c -> c.accept(e)); }
}
```

`GameEvent` 는 sealed interface 이며 `record ExpGained(...)`, `record LeveledUp(...)` 가 구현체.

### 적용처

```
CombatService.finishKill ──publish(ExpGained)──▶ ProgressionSystem.on
                                                  └─ Player.gainExp + publish(LeveledUp)
publish(LeveledUp) ────────────────────▶ GameServer.broadcastProgression
                                          └─ PLAYER_EXP / PLAYER_LEVELUP 송출
```

도메인(EXP 누적) 과 네트워크(패킷 송신) 가 분리됐다. EXP 정책(예: 파티 분배) 추가 시 `ProgressionSystem` 만 수정하면 되고, 네트워크 코드는 손대지 않는다.

## 4. Factory 패턴 변종 — Registry

[`ItemRegistry`](../server/src/main/java/mygame/game/item/ItemRegistry.java),
[`SkillRegistry`](../server/src/main/java/mygame/game/skill/SkillRegistry.java),
[`MonsterRegistry`](../server/src/main/java/mygame/game/entity/MonsterRegistry.java) 모두 같은 형태:

```java
public final class ItemRegistry {
    private static final Map<String, ItemTemplate> TEMPLATES = ...;
    public static ItemTemplate get(String id) { ... }
    public static boolean isEquipment(String id) { ... }
    public static List<String> equipmentIds() { ... }
}
```

순수 Factory 라기보다 "ID → 인스턴스 조회" 를 한 곳에 집중시킨 형태. JSON/DB 로드로 교체할 자리가 마련되어 있다(현재는 정적 코드).

## 5. Decorator 패턴 — Stat 계산

```java
public sealed interface StatProvider permits BaseStats, EquipmentStatDecorator {
    Stats stats();
}
public record BaseStats(int level) implements StatProvider { ... }
public record EquipmentStatDecorator(StatProvider inner, Stats bonus) implements StatProvider {
    public Stats stats() { return inner.stats().plus(bonus); }
}
```

`Equipment.decorate(BaseStats)` 가 슬롯마다 데코레이터를 한 겹씩 두른다. 결과:

```
Player.effectiveStats() = equipment.decorate(new BaseStats(level)).stats();
                         ────────────────────────────────────────
                         체인:  BaseStats → +Weapon → +Hat → +Armor
```

각 슬롯의 보너스 합산이 한 줄에 표현된다. 분기 없음.

## 6. Repository 패턴 — DB

```java
public interface PlayerRepository {
    Optional<PlayerData> findByAccountId(long accountId);
    Optional<PlayerData> findByName(String name);
    PlayerData create(String name, long accountId);
    void save(...);
}
public final class JdbcPlayerRepository implements PlayerRepository { ... }
```

도메인 코드(`GameServer.handleJoin`)는 인터페이스에만 의존. 테스트에서 mock/in-memory 구현으로 바꿔 끼울 수 있다.

## 7. Sealed Type 활용

Java 17+ 의 `sealed interface` 가 도메인 모델을 안전하게 닫는 데 쓰인다.

```java
public sealed interface GameEvent permits ExpGained, LeveledUp {}
public sealed interface Skill permits PowerStrike, TripleBlow, Recovery {}
public sealed interface AuthResult permits AuthOk, AuthFail {}
public sealed interface StatProvider permits BaseStats, EquipmentStatDecorator {}
```

이로 인해 핸들러는 **exhaustive switch** 를 안전하게 작성할 수 있다:

```java
switch (event) {
    case ExpGained e -> { ... }
    case LeveledUp e -> { ... }
    // default 가 필요 없다 — sealed 가 보장
}
```

## 8. Single Source of Truth (META 패킷)

서버 `ItemRegistry` / `SkillRegistry` 가 진실, 클라는 JOIN 직후 META 패킷으로 받아 채운다.
"패턴" 이라기보다 설계 원칙이지만 명시한다 — 클라 하드코딩이 서버와 어긋나는 종류의 버그를 구조적으로 차단.

## 9. Immutable / record 우선

DTO·이벤트·값 객체는 모두 record 또는 final 필드. `Player`/`Monster` 처럼 본질적으로 가변인 것만 mutable, 내부에서 방어적 복사를 쓴다:

```java
public Map<String, Integer> snapshot() {
    return Map.copyOf(items);     // 호출자 측 변경 차단
}
```

## 10. 의도적으로 도입하지 않은 것

| 도구 | 이유 |
|---|---|
| Spring (`@Autowired`/`@RestController`) | "직접 구현해야 본질을 안다" — 전체 학습 목표 |
| Lombok | record + final 로 충분 |
| Bean Validation | 학습 단계에선 명시적 if-throw 가 명확 |
| Connection pool (Hikari) | H2 임베디드라 비용 낮음. 운영 단계 진입 시 도입 |
| Zod (클라 패킷 스키마) | 한 곳만 수정해도 양쪽 흐트러지지 않게 META 로 SSoT 화 했고, 추가 의존성은 YAGNI 원칙으로 보류 |

## 11. 다음 문서

- 개발 이력 · 리팩토링 결과: [09-development-history.md](./09-development-history.md)
