# 04 · 서버 영속성 · 인증

`server/src/main/java/mygame/db/` 와 `server/src/main/java/mygame/auth/` 패키지를 다룬다.
H2 임베디드 + 순수 JDBC + Repository 인터페이스로 구성되어 있고, 인증은 PBKDF2 기반 비밀번호 해싱을 사용한다.

## 1. DB 설치 위치

```java
String home = System.getProperty("user.home");
this.database = new Database(
        "jdbc:h2:file:" + home + "/mygame-data/mygame;AUTO_SERVER=TRUE",
        "sa", "");
```

- 파일 경로: `~/mygame-data/mygame.mv.db`
- `AUTO_SERVER=TRUE` 로 설정해 서버가 떠 있는 동안 H2 콘솔/외부 도구로도 같은 파일을 열 수 있다.
- 비밀번호는 빈 문자열 — 로컬 학습 환경 한정.

## 2. Database — 마이그레이션

[`Database.java`](../server/src/main/java/mygame/db/Database.java) 는 부팅 시 `runMigrations()` 으로 스키마를 보장한다.

```sql
CREATE TABLE IF NOT EXISTS accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) UNIQUE NOT NULL,
    password_hash VARCHAR(256) NOT NULL,
    salt VARCHAR(128) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS players (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE,             -- 계정 1:1 캐릭터
    name VARCHAR(64) UNIQUE NOT NULL,
    level INT NOT NULL DEFAULT 1,
    exp INT NOT NULL DEFAULT 0,
    meso BIGINT NOT NULL DEFAULT 0,
    items_json TEXT NOT NULL DEFAULT '{}',
    equipment_json TEXT NOT NULL DEFAULT '{}',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

`accounts.username` UNIQUE 로 동시 가입 충돌을 막고, `players.name` UNIQUE 로 다른 계정과 캐릭터 이름이 겹치지 않게 한다.

## 3. Repository 인터페이스

```java
public interface AccountRepository {
    Optional<Account> findByUsername(String username);
    Account create(String username, String passwordHash, String salt);
}

public interface PlayerRepository {
    Optional<PlayerData> findByAccountId(long accountId);
    Optional<PlayerData> findByName(String name);
    PlayerData create(String name, long accountId);
    void save(long playerId, int level, int exp, long meso,
              Map<String,Integer> items, Map<String,String> equipment);
}
```

`PlayerData` 는 `record` 로 read-only 데이터 운반 객체.

JDBC 구현체는 `JdbcAccountRepository` / `JdbcPlayerRepository` 두 개이며, **PreparedStatement** 만 사용해 SQL 인젝션을 차단한다. 인벤토리/장비는 Jackson 으로 JSON 직렬화해 단일 컬럼에 저장 — 학습 단계의 단순화이며, 통계/검색이 필요해지면 별도 테이블로 정규화 예정.

## 4. AuthService

[`AuthService.java`](../server/src/main/java/mygame/auth/AuthService.java) 가 로그인·회원가입 도메인.

```java
public sealed interface AuthResult permits AuthOk, AuthFail {}
public record AuthOk(long accountId, String username) implements AuthResult {}
public record AuthFail(String reason) implements AuthResult {}

public AuthResult login(String username, String password) {
    var maybe = accounts.findByUsername(username);
    if (maybe.isEmpty()) return new AuthFail("아이디/비밀번호가 일치하지 않습니다.");
    var acc = maybe.get();
    if (!PasswordHasher.verify(password, acc.salt(), acc.passwordHash())) {
        return new AuthFail("아이디/비밀번호가 일치하지 않습니다.");
    }
    return new AuthOk(acc.id(), acc.username());
}
```

실패 사유를 일부러 동일 메시지로 통일해 사용자명 존재 여부를 흘리지 않는다.

## 5. PasswordHasher

[`PasswordHasher.java`](../server/src/main/java/mygame/auth/PasswordHasher.java) 는 표준 자바의 `PBKDF2WithHmacSHA256` 사용:

```java
public static String hash(String password, String saltBase64) {
    byte[] salt = Base64.getDecoder().decode(saltBase64);
    KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
    SecretKey key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec);
    return Base64.getEncoder().encodeToString(key.getEncoded());
}
public static String generateSalt() { ... }
public static boolean verify(String input, String saltBase64, String expectedHash) { ... }
```

iteration count 120,000 — 학습 프로젝트 기준 충분히 느려 brute force 비용을 만든다.
운영 등급으로는 BCrypt/Argon2 가 정공법이지만, **외부 라이브러리 도입 전 이유 기록** 원칙상
표준 라이브러리만으로 동작하는 길을 택했다 ([CLAUDE.md](../CLAUDE.md) 참고).

## 6. AuthSessions — 연결당 인증 상태

```java
public final class AuthSessions {
    private final AuthService service;
    private final Map<WebSocket, Long> authedConnections = new ConcurrentHashMap<>();

    public Long accountIdOf(WebSocket conn) { return authedConnections.get(conn); }
    public void clear(WebSocket conn) { authedConnections.remove(conn); }

    public void handleLogin(PacketContext ctx) throws Exception { ... }
    public void handleRegister(PacketContext ctx) throws Exception { ... }
}
```

JOIN 핸들러는 **반드시** `auth.accountIdOf(conn) != null` 을 확인한다. 안 그러면 누구라도 임의 이름으로 입장 가능.

## 7. 세션 종료 시 저장

`GameServer.onClose` 가 마지막 보루:

```java
if (player.dbId() > 0) {
    try {
        playerRepo.save(
                player.dbId(),
                player.level(),
                player.exp(),
                player.meso(),
                player.inventory().snapshot(),
                SessionNotifier.equipmentSnapshotAsStringMap(player));
    } catch (Exception e) {
        log.error("플레이어 저장 실패: name={}", player.name(), e);
    }
}
```

저장 실패가 발생해도 연결 정리 자체는 진행한다 — 사용자 한 명의 DB 장애가 다른 세션 정리에 영향을 주지 않도록.

> **현재 한계**: HP/MP 는 영속화하지 않는다. 세션마다 풀 충전. 추후 Phase 에서 추가.

## 8. 동시성 주의

- `accountId` 와 `players.name` 모두 UNIQUE 라 동시 신규 생성 시 한쪽이 SQL Exception 으로 실패. 클라에는 `"이미 사용 중인 캐릭터 이름입니다"` 등 명확한 메시지로 회신.
- DB 연결은 매 호출마다 `database.connection()` 으로 받지만 H2 임베디드라 비용이 낮다. 운영 등급에서는 connection pool(Hikari 등) 도입 예정.

## 9. 다음 문서

- 클라이언트 아키텍처: [05-client-architecture.md](./05-client-architecture.md)
