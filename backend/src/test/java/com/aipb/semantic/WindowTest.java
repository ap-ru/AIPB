package com.aipb.semantic;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Window arithmetic, anchored on the data's today (2026-10-16 in the synthetic data). */
class WindowTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 16);

    private static Window.Range range(String from, String to) { return new Window.Range(LocalDate.parse(from), LocalDate.parse(to)); }

    @Test
    void closedMonthWindowsEndAtTheCurrentMonth() {
        assertThat(Window.of("ytd").resolve(AS_OF, true)).isEqualTo(range("2026-01-01", "2026-10-01"));
        assertThat(Window.of("trailing", "month", 12).resolve(AS_OF, true)).isEqualTo(range("2025-10-01", "2026-10-01"));
        assertThat(Window.of("lastClosed", "quarter", 8).resolve(AS_OF, true)).isEqualTo(range("2024-10-01", "2026-10-01"));
    }

    @Test
    void eventWindowsIncludeToday() {
        assertThat(Window.of("ytd").resolve(AS_OF, false)).isEqualTo(range("2026-01-01", "2026-10-17"));
        assertThat(Window.of("trailing", "day", 30).resolve(AS_OF, false)).isEqualTo(range("2026-09-17", "2026-10-17"));
        assertThat(Window.of("next", "day", 90).resolve(AS_OF, false)).isEqualTo(range("2026-10-16", "2027-01-14"));
        assertThat(Window.of("current", "quarter", 1).resolve(AS_OF, false)).isEqualTo(range("2026-10-01", "2027-01-01"));
    }

    @Test
    void lastClosedQuarterIgnoresTheClosedFlag() {
        assertThat(Window.of("lastClosed", "quarter", 8).resolve(AS_OF, false)).isEqualTo(range("2024-10-01", "2026-10-01"));
    }

    @Test
    void priorYearOfYtdIsTheSameMonthsLastYear() {
        Window ytd = Window.of("ytd");
        Window.Range prior = ytd.resolve(AS_OF, true).minus(ytd.shift("priorYear"));
        assertThat(prior).isEqualTo(range("2025-01-01", "2025-10-01"));
    }

    @Test
    void priorPeriodIsTheWindowsOwnLength() {
        Window t = Window.of("trailing", "month", 3);
        assertThat(t.resolve(AS_OF, true).minus(t.shift("priorPeriod"))).isEqualTo(range("2026-04-01", "2026-07-01"));
        Window q = Window.of("current", "quarter", 1);
        assertThat(q.shift("priorPeriod")).isEqualTo(new Window.Shift("quarter", 1));
        assertThat(Window.of("all").shift("priorPeriod")).isNull();
    }

    @Test
    void ytdEarlyInJanuaryIsEmptyForClosedMonths() {
        assertThat(Window.of("ytd").resolve(LocalDate.of(2027, 1, 5), true).empty()).isTrue();
    }

    @Test
    void malformedWindowsSayWhatIsMissing() {
        assertThat(Window.of("trailing").problems()).anyMatch(p -> p.contains("needs unit")).anyMatch(p -> p.contains("needs n"));
        assertThat(Window.of("someday").problems()).anyMatch(p -> p.contains("must be one of"));
        assertThat(new Window("range", null, null, "2026-05-01", "2026-01-01", null).problems()).anyMatch(p -> p.contains("before"));
        assertThat(Window.of("lastClosed", "quarter", 8).problems()).isEmpty();
    }

    @Test
    void describesInPlainWords() {
        assertThat(Window.of("lastClosed", "quarter", 8).describe()).isEqualTo("last 8 closed quarters");
        assertThat(Window.of("trailing", "month", 1).describe()).isEqualTo("last 1 month");
        assertThat(range("2026-01-01", "2026-10-01").describe()).isEqualTo("Jan 1, 2026 - Sep 30, 2026");
    }
}
