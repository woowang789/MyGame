package mygame.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import mygame.game.entity.Player;
import mygame.game.item.EquipSlot;
import mygame.game.stat.Stats;
import mygame.network.packets.Packets.EquipmentPacket;
import mygame.network.packets.Packets.StatsPacket;

/**
 * 플레이어 본인에게 자주 보내는 상태 패킷(장비, 스탯)의 송신을 단일 책임으로 모은 유틸.
 *
 * <p>핸들러를 도메인별로 분리할 때 공통 송신 로직을 중복 없이 공유하기 위한 얇은 어댑터다.
 * {@link GameServer} 와 각 핸들러 모두 같은 인스턴스를 주입받아 쓴다.
 */
public final class SessionNotifier {

    private final ObjectMapper json;

    public SessionNotifier(ObjectMapper json) {
        this.json = json;
    }

    /** Equipment 의 EnumMap 을 문자열 키 맵(DB · JSON 친화)으로 변환. */
    public static Map<String, String> equipmentSnapshotAsStringMap(Player player) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<EquipSlot, String> e : player.equipment().snapshot().entrySet()) {
            m.put(e.getKey().name(), e.getValue());
        }
        return m;
    }

    /** 최종 스탯 + 현재 HP/MP 를 본인에게 전달. 스킬 사용·장비 변경·피격 시 모두 호출. */
    public void sendStats(Player player) {
        Stats s = player.effectiveStats();
        player.connection().send(PacketEnvelope.wrap(json, "STATS",
                new StatsPacket(s.maxHp(), s.maxMp(), s.attack(), s.speed(), player.hp(), player.mp())));
    }

    /** 본인에게 장비 스냅샷과 최종 스탯을 함께 전달. 공격 피해량 등 UI 에 영향. */
    public void sendEquipmentAndStats(Player player) {
        player.connection().send(PacketEnvelope.wrap(json, "EQUIPMENT",
                new EquipmentPacket(player.id(), equipmentSnapshotAsStringMap(player))));
        sendStats(player);
    }
}
