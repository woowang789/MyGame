package mygame.admin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;
import mygame.db.AccountRepository;
import mygame.db.PlayerRepository;
import mygame.db.PlayerRepository.PlayerData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BroadcastNoticeCommand 가:
 * <ol>
 *   <li>broadcaster 에 메시지를 그대로 전달하고
 *   <li>audit 에 sent 카운트 + preview(80자 자르기) + length 만 기록하며
 *   <li>JSON 안전하게 escape(따옴표·개행) 한다
 * </ol>
 * 를 회귀 테스트로 고정.
 */
class BroadcastNoticeCommandTest {

    @Test
    @DisplayName("정상 호출: broadcaster 에 메시지 전달 + audit 1건")
    void normal_call() {
        var lastBroadcasted = new AtomicReference<String>(null);
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = facade(msg -> { lastBroadcasted.set(msg); return 3; }, auditEntries);

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        String result = new BroadcastNoticeCommand(facade, "점검 5분 전입니다.")
                .execute(session, recordingAudit(auditEntries));

        assertEquals("점검 5분 전입니다.", lastBroadcasted.get());
        assertTrue(result.contains("3"), "결과 메시지에 송신 세션 수가 포함돼야 함");
        assertEquals(1, auditEntries.size());
        assertEquals("BROADCAST_NOTICE", auditEntries.get(0).action());
        String payload = auditEntries.get(0).payload();
        assertTrue(payload.contains("\"sent\":3"));
        assertTrue(payload.contains("\"messageLength\":11"));
    }

    @Test
    @DisplayName("80자 초과 메시지: audit preview 가 80자 + 말줄임표로 잘린다")
    void preview_truncates_long_message() {
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = facade(m -> 0, auditEntries);

        String longMsg = "a".repeat(200);
        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        new BroadcastNoticeCommand(facade, longMsg).execute(session, recordingAudit(auditEntries));

        String preview = extractPreview(auditEntries.get(0).payload());
        assertEquals(80 + 1, preview.length(), "80 + 말줄임표 1자");
        assertTrue(preview.endsWith("…"));
        // length 는 원본을 그대로 반영
        assertTrue(auditEntries.get(0).payload().contains("\"messageLength\":200"));
    }

    @Test
    @DisplayName("따옴표·개행 포함 메시지: audit payload 가 JSON-safe 하게 escape")
    void json_escape_in_payload() {
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = facade(m -> 1, auditEntries);

        String tricky = "안녕\n\"hello\"";
        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        new BroadcastNoticeCommand(facade, tricky).execute(session, recordingAudit(auditEntries));

        String payload = auditEntries.get(0).payload();
        // 원시 \n 또는 unescaped " 가 들어가 있으면 audit_log JSON 이 깨진다.
        assertFalse(payload.contains("\n"), "원시 개행이 payload 에 들어가면 안 됨");
        assertTrue(payload.contains("\\n"), "개행은 \\n 으로 escape");
        assertTrue(payload.contains("\\\"hello\\\""), "따옴표는 \\\" 로 escape");
    }

    // --- helpers ---

    private static AdminFacade facade(java.util.function.ToIntFunction<String> broadcaster,
                                      List<AuditLogRepository.Entry> auditSink) {
        return new AdminFacade(
                List::of,
                emptyAccountRepo(),
                emptyPlayerRepo(),
                mygame.admin.TestRepos.emptyShopRepo(),
                recordingAudit(auditSink),
                () -> {},
                p -> {},
                broadcaster);
    }

    private static String extractPreview(String json) {
        // 가짜 JSON 파서: "preview":" ... "(끝까지) 만 정확히 잘라냄. 본 테스트 한정.
        int start = json.indexOf("\"preview\":\"") + "\"preview\":\"".length();
        int end = json.lastIndexOf("\"}");
        return json.substring(start, end);
    }

    private static AccountRepository emptyAccountRepo() {
        return new AccountRepository() {
            @Override public Optional<Account> findByUsername(String username) { return Optional.empty(); }
            @Override public Optional<AccountSummary> findById(long id) { return Optional.empty(); }
            @Override public Account create(String u, String h, String s) {
                throw new UnsupportedOperationException();
            }
            @Override public List<AccountSummary> findPage(int offset, int limit) { return List.of(); }
            @Override public long count() { return 0; }
            @Override public int setDisabled(long id, boolean d) { return 0; }
            @Override public int updatePassword(long id, String hash, String salt) { return 0; }
        };
    }

    private static PlayerRepository emptyPlayerRepo() {
        return new PlayerRepository() {
            @Override public Optional<PlayerData> findByName(String name) { return Optional.empty(); }
            @Override public Optional<PlayerData> findByAccountId(long accountId) { return Optional.empty(); }
            @Override public PlayerData create(String name, long accountId) {
                throw new UnsupportedOperationException();
            }
            @Override public void save(long id, int level, int exp, long meso, int hp, int mp,
                                       java.util.Map<String, Integer> items,
                                       java.util.Map<String, String> equipment) {}
        };
    }

    private static AuditLogRepository recordingAudit(List<AuditLogRepository.Entry> sink) {
        return new AuditLogRepository() {
            @Override public void append(Long adminId, String adminUsername, String action, String payload) {
                sink.add(new Entry(sink.size() + 1, adminId, adminUsername, action, payload, Instant.now()));
            }
            @Override public List<Entry> recent(int limit) { return List.copyOf(sink); }
        };
    }
}
