package mygame.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import mygame.admin.audit.AuditLogRepository;
import mygame.auth.PasswordHasher;
import mygame.auth.PasswordHasher.Hashed;
import mygame.db.AccountRepository;
import mygame.db.AccountRepository.AccountSummary;
import mygame.db.PlayerRepository;
import mygame.db.PlayerRepository.PlayerData;
import mygame.game.entity.Player;

/**
 * 백오피스의 도메인 게이트웨이.
 *
 * <p>{@code AdminServer}/handler 들은 {@code World}·{@code GameServer} 내부를 직접
 * 만지지 않고 본 클래스만 통해 접근한다. 의도:
 * <ul>
 *   <li>도메인 보호: admin 핸들러 버그가 게임 상태를 직접 망가뜨리지 못하게 인터페이스로 격리.
 *   <li>학습: Spring 의 Service/Facade 추상화가 무엇을 해주는지 직접 짜보기.
 *   <li>테스트성: 핸들러는 본 클래스의 메서드만 의존하므로 가짜 Facade 로 단위 테스트 가능.
 * </ul>
 *
 * <p>접속 플레이어 컬렉션은 {@link Supplier} 로 주입 — {@code GameServer.sessionPlayers}
 * 에 본 클래스가 직접 의존하지 않고 호출 시점의 스냅샷만 받는다.
 */
public final class AdminFacade {

    /** 백오피스 화면용 접속자 요약 — 비밀번호·DB 식별자 등 민감/내부 필드 제외. */
    public record OnlinePlayerView(int sessionId, String name, String mapId, int level, int hp, int mp) {}

    /** 서버 자원 상태 — JVM 기본 정보 정도만 우선 노출. */
    public record ServerStats(long onlineCount, long heapUsedMb, long heapMaxMb, long uptimeSeconds) {}

    private final Supplier<Collection<Player>> playersSupplier;
    private final AccountRepository accountRepo;
    private final PlayerRepository playerRepo;
    private final AuditLogRepository auditRepo;
    /**
     * 강제 저장 액션. 보통 {@code PeriodicSaver::saveAll} 을 가리키지만, 인터페이스 대신
     * {@link Runnable} 로 받아 admin 모듈이 PeriodicSaver 구체 타입에 의존하지 않게 한다
     * (테스트에서 가짜 액션 주입 가능).
     */
    private final Runnable saveAllAction;
    /**
     * 킥 액션. 보통 {@code p -> p.connection().close()} 를 가리킨다 — Java-WebSocket 의
     * onClose 가 발화돼 GameServer 가 평소 disconnect 경로(저장·맵 broadcast)를 그대로 탄다.
     * Consumer 로 추상화한 이유: admin 모듈이 WebSocket 타입에 직접 의존하지 않게 + 테스트.
     */
    private final Consumer<Player> kickAction;
    /**
     * 시스템 공지 broadcaster. 메시지를 받아 송신 성공 세션 수를 돌려준다.
     * 보통 {@code GameServer::broadcastSystemNotice} 를 가리킨다.
     */
    private final ToIntFunction<String> noticeBroadcaster;
    private final long startedAtMillis;

    public AdminFacade(Supplier<Collection<Player>> playersSupplier,
                       AccountRepository accountRepo,
                       PlayerRepository playerRepo,
                       AuditLogRepository auditRepo,
                       Runnable saveAllAction,
                       Consumer<Player> kickAction,
                       ToIntFunction<String> noticeBroadcaster) {
        this.playersSupplier = playersSupplier;
        this.accountRepo = accountRepo;
        this.playerRepo = playerRepo;
        this.auditRepo = auditRepo;
        this.saveAllAction = saveAllAction;
        this.kickAction = kickAction;
        this.noticeBroadcaster = noticeBroadcaster;
        this.startedAtMillis = System.currentTimeMillis();
    }

    public int onlineCount() {
        return playersSupplier.get().size();
    }

    /** 최신 N 명 — 정렬은 호출 시점 컬렉션 그대로(추후 정책 도입 시 정렬 도입). */
    public List<OnlinePlayerView> onlineSnapshot(int limit) {
        Collection<Player> snapshot = playersSupplier.get();
        List<OnlinePlayerView> views = new ArrayList<>(Math.min(snapshot.size(), limit));
        int i = 0;
        for (Player p : snapshot) {
            if (i++ >= limit) break;
            views.add(new OnlinePlayerView(p.id(), p.name(), p.mapId(), p.level(), p.hp(), p.mp()));
        }
        return views;
    }

    public List<AccountSummary> accountsPage(int offset, int limit) {
        return accountRepo.findPage(offset, limit);
    }

    /**
     * 계정 1건과 그에 연결된 플레이어(존재하면) 의 영속화 스냅샷을 함께 돌려준다.
     *
     * <p>주의: 라이브 상태가 아닌 <em>DB 스냅샷</em> 이다 — 접속 중 플레이어의 인메모리
     * 변경(전투 중 HP 등)은 다음 자동 저장 사이클까지 반영되지 않는다. 운영 화면에는
     * "최근 저장 시점 기준" 임을 명시해야 한다.
     */
    public Optional<PlayerData> playerDetailByAccount(long accountId) {
        return playerRepo.findByAccountId(accountId);
    }

    /** 페이지네이션을 거치지 않고 단일 계정만 조회 — 상세 페이지의 헤더용. */
    public Optional<AccountSummary> accountById(long accountId) {
        return accountRepo.findById(accountId);
    }

    /**
     * 운영자 보정 결과. {@code playerExists=false} 면 해당 계정에 캐릭터 자체가 없는 것이고,
     * {@code wasOnline=true} 면 인메모리 Player 에 직접 적용 — 다음 자동 저장 사이클에 DB 반영.
     * {@code wasOnline=false} 면 DB 를 즉시 갱신.
     */
    public record AdjustResult(boolean playerExists, boolean wasOnline, long newValue) {
        public static AdjustResult noPlayer() { return new AdjustResult(false, false, 0); }
        public static AdjustResult online(long v) { return new AdjustResult(true, true, v); }
        public static AdjustResult offline(long v) { return new AdjustResult(true, false, v); }
    }

    /**
     * 메소 조정. delta 는 양수/음수 가능. 0 이하로 클램프.
     *
     * <p>온라인/오프라인 분기:
     * <ul>
     *   <li>접속 중이면 인메모리 Player 객체를 직접 mutate — 다음 자동 저장 사이클에 DB 반영.
     *   <li>접속 중이 아니면 DB 를 즉시 갱신.
     * </ul>
     *
     * <p>경계 race: 본 메서드가 "오프라인" 으로 판단해 DB 에 쓰는 동안 사용자가 로그인하면,
     * JOIN 핸들러가 같은 행을 읽어 인메모리 사본을 만든 직후 다음 autosave 가 admin 쓰기를
     * 덮어쓸 수 있다. 학습 단계에선 빈도가 매우 낮아 별도 락을 두지 않는다 — 발생하면 admin
     * 이 한 번 더 누르면 된다. 프로덕션에서는 single-writer 큐 또는 행 잠금이 필요.
     */
    public AdjustResult adjustMeso(long accountId, long delta) {
        var data = playerRepo.findByAccountId(accountId);
        if (data.isEmpty()) return AdjustResult.noPlayer();
        var snapshot = data.get();
        Player online = findOnlinePlayerByDbId(snapshot.id());
        if (online != null) {
            return AdjustResult.online(online.adjustMeso(delta));
        }
        long newMeso = Math.max(0, snapshot.meso() + delta);
        playerRepo.save(snapshot.id(), snapshot.level(), snapshot.exp(), newMeso,
                snapshot.hp(), snapshot.mp(), snapshot.items(), snapshot.equipment());
        return AdjustResult.offline(newMeso);
    }

    /**
     * EXP 조정. 의도적으로 레벨업 cascade 를 트리거하지 않는다 — 보상/페널티 의미를 명확히
     * 하기 위함. 레벨 자체를 만지는 것은 별도 명령(out of scope).
     */
    public AdjustResult adjustExp(long accountId, int delta) {
        var data = playerRepo.findByAccountId(accountId);
        if (data.isEmpty()) return AdjustResult.noPlayer();
        var snapshot = data.get();
        Player online = findOnlinePlayerByDbId(snapshot.id());
        if (online != null) {
            return AdjustResult.online(online.adjustExp(delta));
        }
        int newExp = Math.max(0, snapshot.exp() + delta);
        playerRepo.save(snapshot.id(), snapshot.level(), newExp, snapshot.meso(),
                snapshot.hp(), snapshot.mp(), snapshot.items(), snapshot.equipment());
        return AdjustResult.offline(newExp);
    }

    /**
     * 비밀번호 리셋. 게임 측 {@link PasswordHasher} 를 그대로 재사용해 동일 알고리즘
     * (PBKDF2 + per-user salt) 로 새 해시를 만들어 DB 만 갱신한다.
     *
     * <p>의도적 보안 결정:
     * <ul>
     *   <li>호출자에게 raw password 문자열은 받되 <em>해시 후에는 어디에도 저장·로깅하지
     *       않는다</em>. AdminCommand 의 audit payload 도 비밀번호를 담지 않는다.
     *   <li>현재 진행 중인 게임 세션은 즉시 무효화하지 않는다 — 라이브 WS 끊기는 별도 명령
     *       (kick) 으로 분리. 학습 단계의 단순화.
     *   <li>비밀번호 검증 규칙(최소 길이) 은 호출 핸들러가 사전에 검증한다 — Facade 는
     *       정책 적용보다 위임에 집중.
     * </ul>
     *
     * @return 변경된 행 수 (id 가 존재하지 않으면 0)
     */
    public int resetPassword(long accountId, String newRawPassword) {
        Hashed h = PasswordHasher.hash(newRawPassword);
        return accountRepo.updatePassword(accountId, h.hash(), h.salt());
    }

    /**
     * 킥 결과. NO_PLAYER = 계정에 캐릭터 자체가 없음 / NOT_ONLINE = 캐릭터는 있지만 미접속 /
     * KICKED = 인메모리 Player 의 WebSocket close 호출 완료(서버 onClose 가 곧 발화).
     */
    public record KickResult(State state, String playerName) {
        public enum State { NO_PLAYER, NOT_ONLINE, KICKED }
        public static KickResult noPlayer() { return new KickResult(State.NO_PLAYER, null); }
        public static KickResult notOnline(String name) { return new KickResult(State.NOT_ONLINE, name); }
        public static KickResult kicked(String name) { return new KickResult(State.KICKED, name); }
    }

    /**
     * 접속 중 플레이어를 강제로 끊는다. WS close 가 trigger 되면 GameServer 의 onClose 가
     * 평소 disconnect 경로(맵 broadcast + DB save) 를 그대로 탄다 — 즉, admin 측이 별도
     * 영속화/정리 코드를 중복하지 않는다는 점이 본 디자인의 핵심.
     */
    public KickResult kickPlayer(long accountId) {
        var data = playerRepo.findByAccountId(accountId);
        if (data.isEmpty()) return KickResult.noPlayer();
        var snapshot = data.get();
        Player online = findOnlinePlayerByDbId(snapshot.id());
        if (online == null) return KickResult.notOnline(snapshot.name());
        kickAction.accept(online);
        return KickResult.kicked(online.name());
    }

    /**
     * 전체 공지 송신. 호출자가 메시지를 사전에 trim/검증한다.
     *
     * @return 실제로 전송에 성공한 세션 수 (오프라인 0 가능)
     */
    public int broadcastSystemNotice(String message) {
        return noticeBroadcaster.applyAsInt(message);
    }

    /** dbId(=player_id) 로 인메모리 접속 플레이어 찾기. O(N) 스캔 — admin 빈도라 OK. */
    private Player findOnlinePlayerByDbId(long playerDbId) {
        for (Player p : playersSupplier.get()) {
            if (p.dbId() == playerDbId) return p;
        }
        return null;
    }

    public long accountCount() {
        return accountRepo.count();
    }

    public List<AuditLogRepository.Entry> recentAudit(int limit) {
        return auditRepo.recent(limit);
    }

    /**
     * "강제 저장" 명령 위임. 호출자(핸들러)가 audit_log 기록까지 책임진다 —
     * 해당 책임은 {@code AdminCommand} 추상에서 다룬다.
     */
    public void forceSaveAll() {
        saveAllAction.run();
    }

    /**
     * 계정 정지/해제 토글. 변경된 행 수를 그대로 반환 — 0 이면 호출자가
     * "존재하지 않는 계정" 으로 처리.
     */
    public int setAccountDisabled(long accountId, boolean disabled) {
        return accountRepo.setDisabled(accountId, disabled);
    }

    public ServerStats stats() {
        Runtime rt = Runtime.getRuntime();
        long heapUsed = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long heapMax = rt.maxMemory() / (1024 * 1024);
        long uptime = (System.currentTimeMillis() - startedAtMillis) / 1000L;
        return new ServerStats(onlineCount(), heapUsed, heapMax, uptime);
    }

    public AuditLogRepository auditRepo() {
        return auditRepo;
    }
}
