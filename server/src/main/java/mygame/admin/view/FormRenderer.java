package mygame.admin.view;

/**
 * 백오피스 핸들러들이 공유하는 작은 폼 컴포넌트 모음.
 *
 * <p>{@link Html} 은 escape/layout 같은 일반 HTML 헬퍼를 담고, 본 클래스는 폼·테이블의
 * 반복되는 입력 행을 모은다. 각 핸들러가 자체 {@code field/hidden/displayRow} 정적
 * 메서드를 갖던 중복(4개 핸들러 × 3~4개 메서드)을 제거하고, 동시에 모든 동적 값에
 * {@link Html#esc(Object)} 를 일관되게 적용해 XSS 안전성을 한 곳에서 보장한다.
 */
public final class FormRenderer {

    private FormRenderer() {}

    /** 일반 입력 행. */
    public static String field(String name, String type, String value,
                               String hint, boolean required) {
        return field(name, type, value, hint, required, "");
    }

    /** {@code extra} 로 추가 속성(data-*, min/max 등)을 주입할 수 있는 풀버전. */
    public static String field(String name, String type, String value,
                               String hint, boolean required, String extra) {
        return "<label class=\"item-field\"><span>" + Html.esc(name) + "</span>"
                + "<input type=\"" + type + "\" name=\"" + name + "\""
                + (required ? " required" : "")
                + " value=\"" + Html.esc(value) + "\""
                + " placeholder=\"" + Html.esc(hint) + "\""
                + (extra == null || extra.isEmpty() ? "" : " " + extra)
                + "></label>";
    }

    /** 숨겨진 입력. */
    public static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + name + "\" value=\""
                + Html.esc(value) + "\">";
    }

    /**
     * 읽기 전용 표시 행. {@code rawHtml} 은 호출자가 책임지고 안전한 HTML 만 넣는다 —
     * 보통 정적 마크업에 {@link Html#esc(Object)} 결과를 끼운 형태.
     */
    public static String displayRow(String name, String rawHtml) {
        return "<label class=\"item-field\"><span>" + Html.esc(name) + "</span>"
                + "<output>" + rawHtml + "</output></label>";
    }

    /** {@code <select>} (enum 값 + selected 표기). 필수 select 이면 required 부여. */
    public static String select(String name, String label, String[] values,
                                String selected, boolean required, String emptyLabel) {
        StringBuilder sb = new StringBuilder("<label class=\"item-field\"><span>")
                .append(Html.esc(label)).append("</span><select name=\"")
                .append(name).append("\"")
                .append(required ? " required" : "").append(">");
        if (emptyLabel != null) {
            sb.append("<option value=\"\"")
              .append(selected == null || selected.isEmpty() ? " selected" : "")
              .append(">").append(Html.esc(emptyLabel)).append("</option>");
        }
        for (String v : values) {
            sb.append("<option value=\"").append(v).append("\"")
              .append(v.equals(selected) ? " selected" : "")
              .append(">").append(v).append("</option>");
        }
        sb.append("</select></label>");
        return sb.toString();
    }

    /**
     * 임의 문자열을 DOM id 토큰으로 안전하게 만든다. 영숫자·{@code _}·{@code -} 외에는
     * {@code _} 로 치환. {@code MonsterDetailHandler}, {@code ShopDetailHandler} 가
     * 행 식별자를 만들 때 동일 로직을 각자 갖던 것을 일원화.
     */
    public static String safeDomId(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        return sb.toString();
    }
}
