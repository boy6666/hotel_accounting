package com.hotel.accounting.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发/校验。access ≤ 2h；refresh ≤ 7d。claim.type：access / refresh。
 */
@Component
public class JwtUtil {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final JwtProperties props;

    public JwtUtil(JwtProperties props) {
        this.props = props;
        byte[] bytes = props.getSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 长度必须 >= 32 字节（HS256 要求）");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String createAccessToken(String username) {
        return createToken(username, TYPE_ACCESS, props.getAccessTtlSeconds());
    }

    public String createRefreshToken(String username) {
        return createToken(username, TYPE_REFRESH, props.getRefreshTtlSeconds());
    }

    private String createToken(String username, String type, long ttlSeconds) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(username)
                .claim("type", type)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验令牌。
     *
     * @throws JwtAuthException 40100（无效/非法）或 40101（过期）
     */
    public Claims parse(String token) {
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token);
            return jws.getPayload();
        } catch (ExpiredJwtException e) {
            throw new JwtAuthException(40101, HttpStatus.UNAUTHORIZED, "令牌已过期，请重新登录或刷新");
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtAuthException(40100, HttpStatus.UNAUTHORIZED, "无效的令牌");
        }
    }

    /**
     * 校验令牌且期望指定类型。
     */
    public Claims parseAndExpect(String token, String expectedType) {
        Claims claims = parse(token);
        String type = claims.get("type", String.class);
        if (!expectedType.equals(type)) {
            throw new JwtAuthException(40100, HttpStatus.UNAUTHORIZED, "令牌类型错误");
        }
        return claims;
    }

    public long getAccessTtlSeconds() {
        return props.getAccessTtlSeconds();
    }
}
