package mygame.game.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 스킬 레지스트리. 스킬 ID → 단일 인스턴스 조회.
 *
 * <p>각 스킬은 상태가 없는 싱글턴({@code INSTANCE})이다. 개별 플레이어의 쿨다운 /
 * MP 는 {@code Player} 쪽에 보관되므로 스킬 객체는 자유롭게 공유된다.
 *
 * <p>전직 확장 시: 새 구현체 클래스를 추가하고 {@link Skill} 의 {@code permits}
 * 목록에 등록, 그리고 아래 {@link #ALL_SKILLS} 에 인스턴스를 추가하면 된다.
 */
public final class SkillRegistry {

    private static final Map<String, Skill> ALL_SKILLS;

    static {
        Map<String, Skill> m = new LinkedHashMap<>();
        // 기본 공격도 스킬 시민. 클라 UI 는 id === "basic_attack" 만 별도 처리.
        put(m, BasicAttack.INSTANCE);
        put(m, PowerStrike.INSTANCE);
        put(m, TripleBlow.INSTANCE);
        put(m, Recovery.INSTANCE);
        ALL_SKILLS = Collections.unmodifiableMap(m);
    }

    private SkillRegistry() {}

    private static void put(Map<String, Skill> m, Skill s) {
        m.put(s.id(), s);
    }

    public static Skill get(String id) {
        Skill s = ALL_SKILLS.get(id);
        if (s == null) throw new IllegalArgumentException("알 수 없는 스킬: " + id);
        return s;
    }

    public static boolean exists(String id) {
        return ALL_SKILLS.containsKey(id);
    }

    /** 지정 직업 태그(또는 BEGINNER 상위 호환)로 접근 가능한 스킬 목록. */
    public static List<Skill> forJob(String job) {
        return ALL_SKILLS.values().stream()
                .filter(s -> s.job().equals(job) || s.job().equals(Skill.JOB_BEGINNER))
                .toList();
    }

    /** 등록된 모든 스킬. META 패킷 생성·테스트용. 반환 순서는 등록 순서를 보존한다. */
    public static List<Skill> all() {
        return List.copyOf(ALL_SKILLS.values());
    }
}
