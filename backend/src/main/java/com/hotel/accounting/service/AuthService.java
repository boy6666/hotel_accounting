package com.hotel.accounting.service;

import com.hotel.accounting.common.BizException;
import com.hotel.accounting.mapper.SysUserMapper;
import com.hotel.accounting.model.SysUser;
import com.hotel.accounting.security.JwtUtil;
import com.hotel.accounting.util.AuditLogger;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 认证服务（BE-01）：登录/刷新/改密。BCrypt 校验；JWT 短期 + 刷新令牌。
 */
@Service
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;
    private final AuditLogger audit;

    public AuthService(SysUserMapper sysUserMapper, JwtUtil jwtUtil, AuditLogger audit) {
        this.sysUserMapper = sysUserMapper;
        this.jwtUtil = jwtUtil;
        this.audit = audit;
    }

    public Map<String, Object> login(String username, String password) {
        if (username == null || password == null) {
            throw BizException.badRequest("用户名与密码不能为空");
        }
        SysUser user = sysUserMapper.selectOneByUsername(username.trim());
        if (user == null || !BCrypt.checkpw(password, user.getPasswordHash())) {
            throw new BizException(40100,
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        audit.log("LOGIN", "用户 " + user.getUsername() + " 登录成功");
        return tokensOf(user);
    }

    public Map<String, Object> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw BizException.badRequest("缺少 refreshToken");
        }
        var claims = jwtUtil.parseAndExpect(refreshToken, JwtUtil.TYPE_REFRESH);
        String username = claims.getSubject();
        SysUser user = sysUserMapper.selectOneByUsername(username);
        if (user == null) {
            throw BizException.unauthorized("账号不存在，请重新登录");
        }
        audit.log("REFRESH", "用户 " + username + " 刷新令牌");
        return tokensOf(user);
    }

    public void changePassword(String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.isBlank()) {
            throw BizException.badRequest("旧密码不能为空");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw BizException.badRequest("新密码长度至少 6 位");
        }
        SysUser user = sysUserMapper.selectOneByUsername("admin");
        if (user == null) {
            throw BizException.unauthorized("账号不存在");
        }
        if (!BCrypt.checkpw(oldPassword, user.getPasswordHash())) {
            throw BizException.unauthorized("旧密码错误");
        }
        user.setPasswordHash(BCrypt.hashpw(newPassword, BCrypt.gensalt(10)));
        sysUserMapper.updateById(user);
        audit.log("CHANGE_PASSWORD", "用户 admin 修改密码");
    }

    private Map<String, Object> tokensOf(SysUser user) {
        String token = jwtUtil.createAccessToken(user.getUsername());
        String refreshToken = jwtUtil.createRefreshToken(user.getUsername());
        return Map.of(
                "token", token,
                "refreshToken", refreshToken,
                "expiresIn", (int) jwtUtil.getAccessTtlSeconds(),
                "user", Map.of("username", user.getUsername(),
                        "displayName", user.getDisplayName() == null ? user.getUsername() : user.getDisplayName())
        );
    }
}
