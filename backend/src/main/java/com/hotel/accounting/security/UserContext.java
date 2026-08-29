package com.hotel.accounting.security;

/**
 * 当前登录用户上下文（基于 ThreadLocal）。
 */
public final class UserContext {

    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<String> DISPLAY_NAME = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(String username, String displayName) {
        USERNAME.set(username);
        DISPLAY_NAME.set(displayName);
    }

    public static String username() {
        String u = USERNAME.get();
        return u == null ? "anonymous" : u;
    }

    public static String displayName() {
        String d = DISPLAY_NAME.get();
        return d == null ? username() : d;
    }

    public static void clear() {
        USERNAME.remove();
        DISPLAY_NAME.remove();
    }
}
