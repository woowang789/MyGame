package mygame.auth;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 비밀번호 해시 유틸.
 *
 * <p>PBKDF2 (HMAC-SHA256, 210k iterations) + per-user random salt.
 * JDK 내장 API 만 사용해 외부 의존을 피한다. bcrypt/Argon2 가 더 이상적이지만
 * 학습 프로젝트 범위에서는 JDK 만으로 충분한 강도를 확보한다.
 *
 * <p>왜 직접 해시·솔트를 다뤄 보는가: Spring Security 로 가기 전에
 * "해시 + 솔트 + iteration" 이 뭘 하는지 손으로 짜봐야 나중에 BCryptPasswordEncoder
 * 가 해주는 일이 체감된다.
 */
public final class PasswordHasher {

    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final String ALGO = "PBKDF2WithHmacSHA256";

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64E = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private PasswordHasher() {}

    public record Hashed(String hash, String salt) {}

    public static Hashed hash(String rawPassword) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        RNG.nextBytes(salt);
        String hash = derive(rawPassword, salt);
        return new Hashed(hash, B64E.encodeToString(salt));
    }

    public static boolean verify(String rawPassword, String expectedHashB64, String saltB64) {
        byte[] salt = B64D.decode(saltB64);
        String actual = derive(rawPassword, salt);
        // 상수 시간 비교. String#equals 는 조기 종료로 타이밍 공격에 취약.
        return constantTimeEquals(actual, expectedHashB64);
    }

    private static String derive(String rawPassword, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(
                    rawPassword.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGO);
            byte[] dk = factory.generateSecret(spec).getEncoded();
            return B64E.encodeToString(dk);
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 파생 실패", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }
}
