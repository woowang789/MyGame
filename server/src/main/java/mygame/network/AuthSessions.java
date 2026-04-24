package mygame.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mygame.auth.AuthService;
import mygame.auth.AuthService.AuthFailure;
import mygame.auth.AuthService.AuthResult;
import mygame.auth.AuthService.AuthSuccess;
import mygame.network.packets.Packets.AuthResponse;
import mygame.network.packets.Packets.LoginRequest;
import mygame.network.packets.Packets.RegisterRequest;
import org.java_websocket.WebSocket;

/**
 * 인증 세션 상태 관리 + LOGIN/REGISTER 핸들러.
 *
 * <p>원래 GameServer 안에 흩어져 있던 인증 맵과 핸들러를 한 곳에 묶었다.
 * 세션 조회 API({@link #accountIdOf}, {@link #clear})는 다른 핸들러(JOIN 등)에서
 * 사용한다.
 *
 * <p>불변 규칙: 한 계정당 동시 세션은 1개. 기존 세션이 있으면 끊고 새 것으로 교체.
 */
public final class AuthSessions {

    private final AuthService authService;
    private final ObjectMapper json;

    /** 로그인 성공 소켓 → accountId. JOIN 은 이 맵에 있는 소켓만 허용. */
    private final Map<WebSocket, Long> authenticatedAccounts = new ConcurrentHashMap<>();
    /** accountId → 현재 세션 소켓. 같은 계정 중복 로그인 감지용 역인덱스. */
    private final Map<Long, WebSocket> accountSessions = new ConcurrentHashMap<>();

    public AuthSessions(AuthService authService, ObjectMapper json) {
        this.authService = authService;
        this.json = json;
    }

    /** 이 소켓이 인증된 accountId 를 반환. 인증 안 된 경우 null. */
    public Long accountIdOf(WebSocket conn) {
        return authenticatedAccounts.get(conn);
    }

    /** 연결 종료 시 호출. 세션 양방향 인덱스 정리. */
    public void clear(WebSocket conn) {
        Long acctId = authenticatedAccounts.remove(conn);
        if (acctId != null) accountSessions.remove(acctId, conn);
    }

    public void handleRegister(PacketContext ctx) throws Exception {
        RegisterRequest req = ctx.json().treeToValue(ctx.body(), RegisterRequest.class);
        AuthResult result = authService.register(req.username(), req.password());
        if (result instanceof AuthFailure f) {
            ctx.conn().send(PacketEnvelope.wrap(json, "AUTH",
                    new AuthResponse(false, f.message(), 0, "")));
            return;
        }
        ctx.conn().send(PacketEnvelope.wrap(json, "AUTH",
                new AuthResponse(true, "", 0, req.username())));
    }

    public void handleLogin(PacketContext ctx) throws Exception {
        LoginRequest req = ctx.json().treeToValue(ctx.body(), LoginRequest.class);
        if (authenticatedAccounts.containsKey(ctx.conn())) {
            ctx.conn().send(PacketEnvelope.wrap(json, "AUTH",
                    new AuthResponse(false, "이미 로그인 상태입니다.", 0, "")));
            return;
        }
        AuthResult result = authService.login(req.username(), req.password());
        if (result instanceof AuthFailure f) {
            ctx.conn().send(PacketEnvelope.wrap(json, "AUTH",
                    new AuthResponse(false, f.message(), 0, "")));
            return;
        }
        long accountId = ((AuthSuccess) result).account().id();
        // 같은 계정의 기존 세션이 있으면 끊는다(서버 1 계정 1 세션 규칙).
        WebSocket prev = accountSessions.put(accountId, ctx.conn());
        if (prev != null && prev != ctx.conn() && prev.isOpen()) {
            prev.send(PacketEnvelope.error(json, "다른 위치에서 로그인되어 연결이 종료됩니다."));
            prev.close();
        }
        authenticatedAccounts.put(ctx.conn(), accountId);
        ctx.conn().send(PacketEnvelope.wrap(json, "AUTH",
                new AuthResponse(true, "", accountId, req.username())));
    }
}
