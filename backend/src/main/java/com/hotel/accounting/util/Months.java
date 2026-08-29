package com.hotel.accounting.util;

import com.hotel.accounting.common.BizException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 月份（YYYY-MM）与日期处理工具。
 */
public final class Months {

    public static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");
    public static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("uuuu-MM");

    private Months() {
    }

    /** 校验并解析 YYYY-MM；非法抛 40000（全局 Handler 映射为 400）。 */
    public static YearMonth require(String month) {
        if (month == null || !MONTH_PATTERN.matcher(month).matches()) {
            throw BizException.badRequest("月份格式应为 YYYY-MM: " + month);
        }
        try {
            return YearMonth.parse(month, MONTH_FMT);
        } catch (DateTimeParseException e) {
            throw BizException.badRequest("月份非法: " + month);
        }
    }

    public static YearMonth parseOrNull(String month) {
        if (month == null || !MONTH_PATTERN.matcher(month).matches()) {
            return null;
        }
        try {
            return YearMonth.parse(month, MONTH_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static String format(YearMonth ym) {
        return ym.format(MONTH_FMT);
    }

    public static LocalDate firstDay(YearMonth ym) {
        return ym.atDay(1);
    }

    public static LocalDate lastDay(YearMonth ym) {
        return ym.atEndOfMonth();
    }

    /** 前一月 */
    public static YearMonth previous(YearMonth ym) {
        return ym.minusMonths(1);
    }

    /** 环比 delta：新值/旧值 - 1；旧值无意义时返回 null */
    public static Double delta(double cur, double prev) {
        if (Double.isNaN(prev) || prev == 0) {
            return null;
        }
        return (cur - prev) / prev;
    }

    public static String trimLocale() {
        return Locale.CHINA.toLanguageTag();
    }
}
