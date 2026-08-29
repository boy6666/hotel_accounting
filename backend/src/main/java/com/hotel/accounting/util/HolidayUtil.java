package com.hotel.accounting.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;

/**
 * 周末/节假日判定。2026 中国法定节假日表简单内置（"简单内置即可"）：
 * <ul>
 *   <li>元旦 1/1–1/3</li>
 *   <li>春节 2/16–2/22（除夕至初七；2026-02-17 正月初一）</li>
 *   <li>清明 4/4–4/6</li>
 *   <li>劳动节 5/1–5/5</li>
 *   <li>端午 6/19–6/21</li>
 *   <li>中秋 9/25–9/27</li>
 *   <li>国庆 10/1–10/8</li>
 * </ul>
 * 调休上班日未建模（简化）。
 */
public final class HolidayUtil {

    private static final Set<LocalDate> HOLIDAYS = new HashSet<>();

    static {
        addRange(2026, 1, 1, 1, 3);
        addRange(2026, 2, 16, 2, 22);
        addRange(2026, 4, 4, 4, 6);
        addRange(2026, 5, 1, 5, 5);
        addRange(2026, 6, 19, 6, 21);
        addRange(2026, 9, 25, 9, 27);
        addRange(2026, 10, 1, 10, 8);
    }

    private HolidayUtil() {
    }

    private static void addRange(int y, int m1, int d1, int m2, int d2) {
        LocalDate from = LocalDate.of(y, m1, d1);
        LocalDate to = LocalDate.of(y, m2, d2);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            HOLIDAYS.add(d);
        }
    }

    public static boolean isHoliday(LocalDate date) {
        return HOLIDAYS.contains(date);
    }

    public static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    /** 是否工作日（weekday = 周末与法定节日以外） */
    public static boolean isWeekday(LocalDate date) {
        return !isWeekend(date) && !isHoliday(date);
    }

    public static int[] monthRange(YearMonth ym) {
        return new int[]{ym.lengthOfMonth()};
    }
}
