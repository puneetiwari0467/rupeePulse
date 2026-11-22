package com.rupeepulse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DailyModelBudgetTests {
    @TempDir Path dir;
    private final Clock today = Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC);

    @Test void limitSurvivesRestartAndResetsNextUtcDay() throws IOException {
        new DailyModelBudget(dir, 1, today).reserve();
        assertThatThrownBy(() -> new DailyModelBudget(dir, 1, today).reserve()).isInstanceOf(DailyModelBudget.Limit.class);
        assertThatCode(() -> new DailyModelBudget(dir, 1, Clock.offset(today,Duration.ofDays(1))).reserve()).doesNotThrowAnyException();
    }

    @Test void damagedCounterDoesNotAllowAnotherRequest() throws IOException {
        Files.writeString(dir.resolve("calls-2026-11-21"), "unreadable");
        assertThatThrownBy(() -> new DailyModelBudget(dir, 1, today).reserve()).isInstanceOf(IOException.class);
    }
}
