package com.hotel.accounting.security;

import com.hotel.accounting.mapper.SysUserMapper;
import com.hotel.accounting.model.SysUser;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * JWT 认证拦截器。白名单：登录/刷新/健康检查/模板下载；其余接口要求 Bearer access token。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    /** 无需认证的路径 */
    private static final Set<String> WHITELIST = Set.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/health"
    );

    private final JwtUtil jwtUtil;
    private final SysUserMapper sysUserMapper;

    public JwtInterceptor(JwtUtil jwtUtil, SysUserMapper sysUserMapper) {
        this.jwtUtil = jwtUtil;
        this.sysUserMapper = sysUserMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检直接放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (WHITELIST.contains(path)) {
            return true;
        }

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeUnauthorized(response, 40100, "未登录，请先登录");
            return false;
        }
        String token = auth.substring(7).trim();
        if (token.isEmpty()) {
            writeUnauthorized(response, 40100, "未登录，请先登录");
            return false;
        }
        try {
            Claims claims = jwtUtil.parseAndExpect(token, JwtUtil.TYPE_ACCESS);
            String username = claims.getSubject();
            String displayName = username;
            SysUser user = sysUserMapper.selectOneByUsername(username);
            if (user != null && user.getDisplayName() != null) {
                displayName = user.getDisplayName();
            }
            UserContext.set(username, displayName);
            return true;
        } catch (JwtAuthException e) {
            writeUnauthorized(response, e.getCode(), e.getMessage());
            return false;
        }
    }

    private void writeUnauthorized(HttpServletResponse response, int code, String message) {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        try {
            response.getWriter().write(
                    "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
        } catch (Exception ignored) {
            // ignore
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
