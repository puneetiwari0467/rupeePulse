package com.rupeepulse;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;

final class LedgerDates {
    // The seeded statements and the Wednesday autopay story share one reporting date.
    static final LocalDate AS_OF = LocalDate.of(2026, 11, 21);
    static final YearMonth MONTH = YearMonth.from(AS_OF);
    private static final List<DateTimeFormatter> FORMATS = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
        format("d MMM uuuu"), format("d/M/uuuu"), format("d-M-uuuu"), format("d-MMM-uuuu"));

    private LedgerDates() {}

    private static DateTimeFormatter format(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    static LocalDate date(String timestamp) {
        String value = timestamp.trim().replaceFirst("^(Mon|Tue|Wed|Thu|Fri|Sat|Sun),\\s*", "").split(",", 2)[0].trim();
        for (var formatter : FORMATS) {
            try { return LocalDate.parse(value, formatter); } catch (DateTimeParseException ignored) { /* try next accepted format */ }
        }
        throw new IllegalArgumentException("Use a date such as 2026-11-21 or 21 Nov 2026.");
    }
}
