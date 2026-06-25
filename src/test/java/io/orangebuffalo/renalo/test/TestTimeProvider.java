package io.orangebuffalo.renalo.test;

import io.orangebuffalo.renalo.time.TimeProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

public class TestTimeProvider implements TimeProvider {
    public static final Instant DEFAULT_TIME = Instant.parse("2099-06-14T08:00:00Z");
    public static final LocalDate DEFAULT_DATE = LocalDate.ofInstant(DEFAULT_TIME, ZoneOffset.UTC);

    @Override
    public Instant now() {
        return DEFAULT_TIME;
    }

    public void reset() {
    }
}
