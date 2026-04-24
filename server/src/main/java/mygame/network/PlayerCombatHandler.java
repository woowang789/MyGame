package mygame.network;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import mygame.game.CombatListener;
import mygame.game.GameMap;
import mygame.game.World;
import mygame.game.entity.Monster;
import mygame.game.entity.Player;
import mygame.network.packets.Packets.PlayerDamagedPacket;
import mygame.network.packets.Packets.PlayerDiedPacket;
import mygame.network.packets.Packets.PlayerRespawnedPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 플레이어 피격·사망·부활 흐름을 한 곳에서 관리.
 *
 * <p>원래 GameServer 안에 익명 {@link CombatListener} + 부활 스케줄러가
 * 흩어져 있었지만, 한 기능 단위로 묶는 편이 읽기 쉽고 부활 정책(지연 시간 등)
 * 변경도 독립적으로 다룰 수 있다.
 *
 * <p>네트워크 계층에 두는 이유: 패킷 포맷을 직접 만들어 송신하므로 network 패키지.
 * 스탯 동기화는 호출자가 주입한 {@link Consumer} 콜백을 통해 위임한다.
 */
public final class PlayerCombatHandler implements CombatListener {

    private static final Logger log = LoggerFactory.getLogger(PlayerCombatHandler.class);
    private static final long RESPAWN_DELAY_MS = 3000;

    private final World world;
    private final Consumer<Player> statsSender;
    private final ScheduledExecutorService respawnExec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "respawn");
                t.setDaemon(true);
                return t;
            });

    public PlayerCombatHandler(World world, Consumer<Player> statsSender) {
        this.world = world;
        this.statsSender = statsSender;
    }

    @Override
    public void onPlayerDamaged(Player target, Monster attacker, int dmgApplied) {
        GameMap m = world.map(target.mapId());
        if (m == null) return;
        int maxHp = target.effectiveStats().maxHp();
        // 공격자 id 는 "몬스터 구분용 음수" 로 인코딩해 플레이어 id 와 섞이지 않게 한다.
        m.broadcast("PLAYER_DAMAGED",
                new PlayerDamagedPacket(target.id(), dmgApplied, target.hp(), maxHp, -attacker.id()));
        statsSender.accept(target);
    }

    @Override
    public void onPlayerDied(Player target, Monster killer) {
        GameMap m = world.map(target.mapId());
        if (m != null) m.broadcast("PLAYER_DIED", new PlayerDiedPacket(target.id()));
        scheduleRespawn(target);
    }

    private void scheduleRespawn(Player target) {
        respawnExec.schedule(() -> {
            try {
                if (!target.connection().isOpen()) return;
                GameMap current = world.map(target.mapId());
                if (current == null) return;
                target.moveTo(current.id(), current.spawnX(), current.spawnY());
                target.fullHealHp();
                target.fullHealMp();
                current.broadcast("PLAYER_RESPAWN",
                        new PlayerRespawnedPacket(target.id(), current.id(),
                                target.x(), target.y(), target.hp()));
                statsSender.accept(target);
            } catch (Exception e) {
                log.error("리스폰 처리 실패 playerId={}", target.id(), e);
            }
        }, RESPAWN_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        respawnExec.shutdownNow();
    }
}
