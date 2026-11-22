package com.rupeepulse;

import com.rupeepulse.Models.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class StatementImport {
    record Row(LocalDate date, String merchant, BigDecimal amount, Direction direction, String source) {}
    private StatementImport() {}

    static List<Row> read(String input) {
        if (input == null || input.length() > 100_000) throw new IllegalArgumentException("Choose a CSV under 100,000 characters.");
        var rows = new ArrayList<Row>();
        String[] lines = input.strip().replaceFirst("^\\uFEFF", "").split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            try {
                var c = fields(lines[i]);
                if (i == 0 && c.get(0).equalsIgnoreCase("date")) continue;
                if (c.size() != 4 || c.get(1).isBlank())
                    throw new IllegalArgumentException("Expected Date, Merchant, Amount, DEBIT or CREDIT.");
                String numeric = c.get(2);
                if (!numeric.matches("(?:\\d+|\\d{1,3}(?:,\\d{2,3})*,\\d{3})(?:\\.\\d{1,2})?"))
                    throw new IllegalArgumentException("Amount must be positive, with at most two decimal places.");
                BigDecimal amount = new BigDecimal(numeric.replace(",", ""));
                if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be greater than zero.");
                Direction direction = switch (c.get(3).toUpperCase(Locale.ROOT)) {
                    case "DEBIT", "DR" -> Direction.DEBIT;
                    case "CREDIT", "CR" -> Direction.CREDIT;
                    default -> throw new IllegalArgumentException("Direction must be DEBIT or CREDIT.");
                };
                rows.add(new Row(LedgerDates.date(c.get(0)), c.get(1), amount, direction, lines[i]));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Row " + (i + 1) + ": " + e.getMessage() + " No rows imported.");
            }
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("The statement has no transaction rows.");
        return rows;
    }

    private static List<String> fields(String line) {
        var result = new ArrayList<String>();
        var value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) { result.add(value.toString().trim()); value.setLength(0); }
            else value.append(c);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quote in CSV field.");
        result.add(value.toString().trim());
        return result;
    }
}
