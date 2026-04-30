package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.command.MonsterUpsertCommand;
import mygame.admin.filter.AuthFilter;
import mygame.admin.view.Html;
import mygame.game.entity.MonsterTemplate;
import mygame.game.item.DropTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST /admin/actions/monster-upsert — 부모 행만 갱신. drop table 은 별도 엔드포인트.
 *
 * <p>새 몬스터 생성 시에는 빈 DropTable 로 초기화. 기존 몬스터 갱신 시에는 현재 drop 을
 * 보존(repository 가 부모만 MERGE 하므로 자식 행은 영향 없음).
 */
public final class MonsterUpsertHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(MonsterUpsertHandler.class);

    private final AdminFacade facade;
    private final AuditLogRepository audit;

    public MonsterUpsertHandler(AdminFacade facade, AuditLogRepository audit) {
        this.facade = facade;
        this.audit = audit;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtils.sendText(ex, 405, "Method Not Allowed");
            return;
        }
        try {
            Map<String, String> form = HttpUtils.parseFormBody(ex);
            MonsterTemplate t = buildFromForm(form);
            String message = new MonsterUpsertCommand(facade, t).execute(AuthFilter.sessionOf(ex), audit);
            HttpUtils.sendHtml(ex, 200, "<span class=\"ok\">" + Html.esc(message) + "</span>");
        } catch (IllegalArgumentException iae) {
            HttpUtils.sendHtml(ex, 400, "<span class=\"error\">" + Html.esc(iae.getMessage()) + "</span>");
        } catch (Exception e) {
            log.error("몬스터 upsert 실패", e);
            HttpUtils.sendHtml(ex, 500, "<span class=\"error\">내부 오류</span>");
        }
    }

    private MonsterTemplate buildFromForm(Map<String, String> form) {
        String id = form.getOrDefault("id", "").trim();
        String name = form.getOrDefault("display_name", "").trim();
        if (id.isEmpty() || name.isEmpty()) {
            throw new IllegalArgumentException("id / display_name 은 비어있을 수 없습니다.");
        }
        int maxHp = Integer.parseInt(form.getOrDefault("max_hp", "100").trim());
        int atk = Integer.parseInt(form.getOrDefault("attack_damage", "10").trim());
        long atkInterval = Long.parseLong(form.getOrDefault("attack_interval_ms", "1500").trim());
        double speed = Double.parseDouble(form.getOrDefault("speed", "60").trim());
        int exp = Integer.parseInt(form.getOrDefault("exp_reward", "10").trim());
        long respawn = Long.parseLong(form.getOrDefault("respawn_delay_ms", "5000").trim());
        int mesoMin = Integer.parseInt(form.getOrDefault("meso_min", "1").trim());
        int mesoMax = Integer.parseInt(form.getOrDefault("meso_max", "10").trim());
        if (mesoMin < 0 || mesoMax < mesoMin) {
            throw new IllegalArgumentException("meso 범위가 올바르지 않습니다 (min<=max, 0 이상).");
        }
        int color = parseColor(form.getOrDefault("body_color", "0xffffff"));
        // 부모 행만 갱신 — drop table 은 기존 사본을 그대로 보존하기 위해 facade 가 자식 행을
        // 건드리지 않는 upsertTemplate 만 호출. 새 몬스터의 경우 drop 은 빈 상태로 시작.
        DropTable existing = facade.monsterTemplate(id)
                .map(MonsterTemplate::dropTable)
                .orElseGet(() -> new DropTable(List.of()));
        return new MonsterTemplate(id, name, maxHp, atk, atkInterval, speed,
                exp, respawn, mesoMin, mesoMax, existing, color);
    }

    private static int parseColor(String s) {
        s = s.trim();
        if (s.startsWith("#")) s = "0x" + s.substring(1);
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Integer.parseUnsignedInt(s.substring(2), 16);
        }
        return Integer.parseInt(s);
    }
}
