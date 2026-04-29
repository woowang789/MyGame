package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.view.Html;
import mygame.db.AccountRepository.AccountSummary;
import mygame.db.PlayerRepository.PlayerData;

/**
 * GET /admin/accounts/{id} — 계정 + 연결된 플레이어 영속화 스냅샷(read-only).
 *
 * <p>표시 데이터는 모두 DB 기준 — 접속 중 플레이어의 인메모리 변화(전투 중 HP 등) 는
 * 다음 자동 저장 사이클(30s) 까지는 반영되지 않는다. 화면 상단에 명시.
 */
public final class AccountDetailHandler implements HttpHandler {

    private static final String PREFIX = "/admin/accounts/";

    private final AdminFacade facade;

    public AccountDetailHandler(AdminFacade facade) {
        this.facade = facade;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.startsWith(PREFIX)) {
            HttpUtils.sendText(ex, 404, "Not Found");
            return;
        }
        String idPart = path.substring(PREFIX.length());
        long accountId;
        try {
            accountId = Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            HttpUtils.sendText(ex, 400, "잘못된 accountId: " + idPart);
            return;
        }
        Optional<AccountSummary> account = facade.accountById(accountId);
        if (account.isEmpty()) {
            HttpUtils.sendHtml(ex, 404, Html.layout("Account not found",
                    "<h1>계정 없음</h1><p>id=" + accountId + " 인 계정을 찾지 못했습니다.</p>"));
            return;
        }
        Optional<PlayerData> player = facade.playerDetailByAccount(accountId);
        HttpUtils.sendHtml(ex, 200, Html.layout(
                "Account #" + accountId,
                renderBody(account.get(), player)));
    }

    private static String renderBody(AccountSummary account, Optional<PlayerData> playerOpt) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p><a href=\"/admin/accounts\">← 목록으로</a></p>");
        sb.append("<h1>")
          .append(Html.esc(account.username()))
          .append(" <span class=\"muted\">#")
          .append(account.id()).append("</span></h1>");
        sb.append("<p class=\"meta\">가입: ")
          .append(account.createdAt() == null ? "-" : Html.esc(account.createdAt().toString()))
          .append(" · 상태: ")
          .append(account.disabled()
                  ? "<span class=\"badge banned\">정지</span>"
                  : "<span class=\"badge active\">활성</span>")
          .append("</p>");

        sb.append("<p class=\"empty\">표시 정보는 마지막 자동 저장(30s 주기) 시점 기준입니다.</p>");

        if (playerOpt.isEmpty()) {
            sb.append("<section><h2>플레이어</h2>")
              .append("<p class=\"empty\">이 계정으로 생성된 캐릭터가 없습니다.</p>")
              .append("</section>");
            return sb.toString();
        }
        PlayerData p = playerOpt.get();

        sb.append("<section><h2>스탯</h2>")
          .append("<table class=\"data-table\"><tbody>")
          .append(row("이름", Html.esc(p.name())))
          .append(row("레벨", Integer.toString(p.level())))
          .append(row("EXP", Integer.toString(p.exp())))
          .append(row("HP", hpMpDisplay(p.hp())))
          .append(row("MP", hpMpDisplay(p.mp())))
          .append(row("메소", String.format("%,d", p.meso())))
          .append("</tbody></table></section>");

        sb.append("<section><h2>운영자 조정</h2>")
          .append("<p class=\"empty\">delta 입력 후 적용. 양수=지급, 음수=차감.</p>")
          .append(adjustForm(account.id(), "MESO", "메소"))
          .append(adjustForm(account.id(), "EXP", "EXP"))
          .append("<p id=\"adjust-result\" class=\"action-result\"></p>")
          .append("</section>");

        sb.append("<section><h2>보안</h2>")
          .append("<p class=\"empty\">비밀번호 리셋은 즉시 DB 에 반영. 진행 중인 게임 세션은 끊기지 않으며, 다음 로그인부터 새 비밀번호로 접속 가능.</p>")
          .append(resetPasswordForm(account.id(), account.username()))
          .append("<p id=\"reset-result\" class=\"action-result\"></p>")
          .append("</section>");

        sb.append("<section><h2>인벤토리</h2>");
        if (p.items().isEmpty()) {
            sb.append("<p class=\"empty\">비어있음</p>");
        } else {
            sb.append("<table class=\"data-table\"><thead><tr><th>아이템</th><th>수량</th></tr></thead><tbody>");
            for (Map.Entry<String, Integer> e : p.items().entrySet()) {
                sb.append("<tr><td>").append(Html.esc(e.getKey())).append("</td>")
                  .append("<td>").append(e.getValue()).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("</section>");

        sb.append("<section><h2>장비</h2>");
        if (p.equipment().isEmpty()) {
            sb.append("<p class=\"empty\">착용 중 장비 없음</p>");
        } else {
            sb.append("<table class=\"data-table\"><thead><tr><th>슬롯</th><th>아이템</th></tr></thead><tbody>");
            for (Map.Entry<String, String> e : p.equipment().entrySet()) {
                sb.append("<tr><td>").append(Html.esc(e.getKey())).append("</td>")
                  .append("<td>").append(Html.esc(e.getValue())).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("</section>");

        return sb.toString();
    }

    private static String row(String key, String value) {
        return "<tr><th>" + Html.esc(key) + "</th><td>" + value + "</td></tr>";
    }

    /**
     * 비밀번호 리셋 폼. 폼 자체는 평문 비밀번호를 brower → server 까지만 전달하고,
     * 응답에는 비밀번호를 다시 echo 하지 않는다. autocomplete=new-password 로 브라우저
     * 자동완성을 분리.
     */
    private static String resetPasswordForm(long accountId, String username) {
        return "<form hx-post=\"/admin/actions/reset-password\" hx-target=\"#reset-result\" "
                + "hx-swap=\"innerHTML\" class=\"adjust-form\" "
                + "hx-confirm=\"" + Html.esc(username) + " 의 비밀번호를 리셋합니다. 진행할까요?\">"
                + "<input type=\"hidden\" name=\"accountId\" value=\"" + accountId + "\">"
                + "<label>새 비밀번호 "
                + "<input type=\"password\" name=\"newPassword\" required minlength=\"6\" maxlength=\"128\" "
                + "autocomplete=\"new-password\" placeholder=\"6자 이상\">"
                + "</label>"
                + "<button type=\"submit\">비밀번호 리셋</button>"
                + "</form>";
    }

    /**
     * 한 종류(meso/exp)에 대한 delta 입력 폼. 결과는 같은 result span 으로 swap 되어
     * 운영자가 메소/EXP 를 번갈아 적용해도 마지막 결과가 누적 표시되지 않고 갱신된다.
     */
    private static String adjustForm(long accountId, String kind, String label) {
        return "<form hx-post=\"/admin/actions/adjust-player\" hx-target=\"#adjust-result\" "
                + "hx-swap=\"innerHTML\" class=\"adjust-form\" "
                + "hx-confirm=\"" + Html.esc(label) + " 보정을 적용합니다. 진행할까요?\">"
                + "<input type=\"hidden\" name=\"accountId\" value=\"" + accountId + "\">"
                + "<input type=\"hidden\" name=\"kind\" value=\"" + kind + "\">"
                + "<label>" + Html.esc(label) + " delta "
                + "<input type=\"number\" name=\"delta\" required step=\"1\" placeholder=\"예: 1000 또는 -500\">"
                + "</label>"
                + "<button type=\"submit\">적용</button>"
                + "</form>";
    }

    /** -1 sentinel 은 "다음 로드 시 풀피 복원" — 사용자에게는 "최대" 로 표기. */
    private static String hpMpDisplay(int v) {
        return v < 0 ? "최대(복원 대기)" : Integer.toString(v);
    }
}
