package io.orangebuffalo.renalo.test;

import io.orangebuffalo.renalo.time.TimeProvider;

import java.time.Instant;

public class TestTimeProvider implements TimeProvider {
    public static final Instant DEFAULT_TIME = Instant.parse("2099-06-14T08:00:00Z");

    private Instant now = DEFAULT_TIME;

    @Override
    public Instant now() {
        return now;
    }

    public void setNow(Instant now) {
        this.now = now;
    }

    public void reset() {
        now = DEFAULT_TIME;
    }
}
