package mygame.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import mygame.admin.audit.AuditLogRepository;
import mygame.db.AccountRepository;
import mygame.db.AccountRepository.AccountSummary;
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
    private final AuditLogRepository auditRepo;
    /**
     * 강제 저장 액션. 보통 {@code PeriodicSaver::saveAll} 을 가리키지만, 인터페이스 대신
     * {@link Runnable} 로 받아 admin 모듈이 PeriodicSaver 구체 타입에 의존하지 않게 한다
     * (테스트에서 가짜 액션 주입 가능).
     */
    private final Runnable saveAllAction;
    private final long startedAtMillis;

    public AdminFacade(Supplier<Collection<Player>> playersSupplier,
                       AccountRepository accountRepo,
                       AuditLogRepository auditRepo,
                       Runnable saveAllAction) {
        this.playersSupplier = playersSupplier;
        this.accountRepo = accountRepo;
        this.auditRepo = auditRepo;
        this.saveAllAction = saveAllAction;
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
