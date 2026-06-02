package io.github.maaasu.astralRecord.feature.loginbonus.view;

import org.jetbrains.annotations.NotNull;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * ログイン報酬の祝日ボーナス判定を行います。
 */
public final class LoginBonusHoliday {
    private LoginBonusHoliday() {
    }

    /**
     * 日本の祝日または振替休日かを判定します。
     *
     * @param date 判定日
     * @return 祝日または振替休日の場合は true
     */
    public static boolean isJapaneseHoliday(@NotNull LocalDate date) {
        Set<LocalDate> holidays = holidaysOfYear(date.getYear());
        return holidays.contains(date) || substituteHolidays(holidays).contains(date);
    }

    private static @NotNull Set<LocalDate> holidaysOfYear(int year) {
        Set<LocalDate> holidays = new HashSet<>();
        holidays.add(LocalDate.of(year, 1, 1));
        holidays.add(nthMonday(year, 1, 2));
        holidays.add(LocalDate.of(year, 2, 11));
        if (year >= 2020) {
            holidays.add(LocalDate.of(year, 2, 23));
        }
        holidays.add(vernalEquinox(year));
        holidays.add(LocalDate.of(year, 4, 29));
        holidays.add(LocalDate.of(year, 5, 3));
        holidays.add(LocalDate.of(year, 5, 4));
        holidays.add(LocalDate.of(year, 5, 5));
        holidays.add(nthMonday(year, 7, 3));
        holidays.add(LocalDate.of(year, 8, 11));
        holidays.add(nthMonday(year, 9, 3));
        holidays.add(autumnalEquinox(year));
        holidays.add(nthMonday(year, 10, 2));
        holidays.add(LocalDate.of(year, 11, 3));
        holidays.add(LocalDate.of(year, 11, 23));
        addCitizensHolidays(holidays, year);
        return holidays;
    }

    private static @NotNull Set<LocalDate> substituteHolidays(@NotNull Set<LocalDate> holidays) {
        Set<LocalDate> substitutes = new HashSet<>();
        for (LocalDate holiday : holidays) {
            if (holiday.getDayOfWeek() != DayOfWeek.SUNDAY) {
                continue;
            }
            LocalDate substitute = holiday.plusDays(1);
            while (holidays.contains(substitute) || substitutes.contains(substitute)) {
                substitute = substitute.plusDays(1);
            }
            substitutes.add(substitute);
        }
        return substitutes;
    }

    private static void addCitizensHolidays(@NotNull Set<LocalDate> holidays, int year) {
        LocalDate date = LocalDate.of(year, 1, 2);
        LocalDate end = LocalDate.of(year, 12, 30);
        while (date.isBefore(end)) {
            if (!holidays.contains(date) && holidays.contains(date.minusDays(1)) && holidays.contains(date.plusDays(1))) {
                holidays.add(date);
            }
            date = date.plusDays(1);
        }
    }

    private static @NotNull LocalDate nthMonday(int year, int month, int nth) {
        LocalDate date = LocalDate.of(year, month, 1);
        while (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            date = date.plusDays(1);
        }
        return date.plusWeeks(nth - 1L);
    }

    private static @NotNull LocalDate vernalEquinox(int year) {
        int day = (int) Math.floor(20.8431 + 0.242194 * (year - 1980) - Math.floor((year - 1980) / 4.0));
        return LocalDate.of(year, 3, day);
    }

    private static @NotNull LocalDate autumnalEquinox(int year) {
        int day = (int) Math.floor(23.2488 + 0.242194 * (year - 1980) - Math.floor((year - 1980) / 4.0));
        return LocalDate.of(year, 9, day);
    }
}
