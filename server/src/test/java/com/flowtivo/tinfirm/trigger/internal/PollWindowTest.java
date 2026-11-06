package com.flowtivo.tinfirm.trigger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Where a poll's next pass starts (the package 4 contract, §5.4): the overlap, never backwards, never past the settle
 * time's cutoff, the offset of a bulk change, several listings, a failed listing holding it.
 */
class PollWindowTest {

    private static final Duration OVERLAP = Duration.ofMinutes(2);

    private static final Instant SINCE = Instant.parse("2026-10-05T10:00:00Z");

    private static final Instant CUTOFF = SINCE.plusSeconds(3600);

    private static PollWindow.Seen complete(Instant newest) {
        return new PollWindow.Seen(newest, null, false, List.of(), null);
    }

    @Test
    void aCompletePassMovesTheBoundToItsNewestChangeMinusTheOverlapNeverBackNeverPastTheCutoff() {
        PollWindow window = new PollWindow(SINCE, List.of("A-1", "A-2"), null);

        assertThat(PollWindow.next(window, List.of(complete(SINCE.plusSeconds(600))), CUTOFF, OVERLAP,
                PollWindowTest::minute)).isEqualTo(new PollWindow(SINCE.plusSeconds(480), List.of(), null));
        assertThat(PollWindow.next(window, List.of(complete(SINCE.plusSeconds(60))), CUTOFF, OVERLAP,
                PollWindowTest::minute)).isEqualTo(new PollWindow(SINCE, List.of(), null));
        assertThat(PollWindow.next(window, List.of(new PollWindow.Seen(null, null, false, List.of(), null)), CUTOFF,
                OVERLAP, PollWindowTest::minute)).isEqualTo(new PollWindow(SINCE, List.of(), null));
        assertThat(PollWindow.next(window, List.of(complete(CUTOFF.plusSeconds(600))), CUTOFF, OVERLAP,
                PollWindowTest::minute).since()).isEqualTo(CUTOFF);
        assertThat(PollWindow.first(SINCE, OVERLAP)).isEqualTo(new PollWindow(SINCE.minus(OVERLAP), List.of(),
                null));
    }

    @Test
    void aCutPassContinuesAtAnOffsetWhenTheBoundWouldNotMoveByItsPrecision() {
        PollWindow window = new PollWindow(SINCE, List.of(), null);

        assertThat(PollWindow.next(window, List.of(new PollWindow.Seen(SINCE.plusSeconds(900),
                SINCE.plusSeconds(900), false, List.of("A-1"), "c1")), CUTOFF, OVERLAP, PollWindowTest::minute))
            .isEqualTo(new PollWindow(SINCE.plusSeconds(780), List.of(), null));
        PollWindow bulk = PollWindow.next(window, List.of(new PollWindow.Seen(SINCE.plusSeconds(150),
                SINCE.plusSeconds(150), false, List.of("A-1", "A-2"), "c1")), CUTOFF, OVERLAP, PollWindowTest::minute);
        assertThat(bulk).isEqualTo(new PollWindow(SINCE, List.of("A-1", "A-2"), "c1"));
        assertThat(bulk.offset()).isEqualTo(2);
        PollWindow further = PollWindow.next(bulk, List.of(new PollWindow.Seen(SINCE.plusSeconds(150),
                SINCE.plusSeconds(150), false, List.of("A-3"), "c2")), CUTOFF, OVERLAP, PollWindowTest::minute);
        assertThat(further).isEqualTo(new PollWindow(SINCE, List.of("A-1", "A-2", "A-3"), "c2"));
    }

    @Test
    void aFailedListingHoldsTheWindow() {
        PollWindow window = new PollWindow(SINCE, List.of("A-1"), "c1");

        assertThat(PollWindow.next(window, List.of(PollWindow.Seen.failure()), CUTOFF, OVERLAP,
                PollWindowTest::minute)).isEqualTo(window);
        assertThat(PollWindow.next(new PollWindow(SINCE, List.of(), null), List.of(complete(SINCE.plusSeconds(900)),
                PollWindow.Seen.failure()), CUTOFF, OVERLAP, PollWindowTest::second))
            .isEqualTo(new PollWindow(SINCE, List.of(), null));
    }

    @Test
    void severalListingsMoveToTheEarliestPointEveryListingReached() {
        PollWindow window = new PollWindow(SINCE, List.of(), null);

        assertThat(PollWindow.next(window, List.of(complete(SINCE.plusSeconds(600)),
                new PollWindow.Seen(null, null, false, List.of(), null)), CUTOFF, OVERLAP, PollWindowTest::second))
            .isEqualTo(new PollWindow(SINCE.plusSeconds(480), List.of(), null));
        // A cut listing whose bound would not move still makes progress, without the overlap.
        assertThat(PollWindow.next(window, List.of(new PollWindow.Seen(SINCE.plusSeconds(60),
                SINCE.plusSeconds(60), false, List.of("o/r#1"), null), complete(SINCE.plusSeconds(900))), CUTOFF,
                OVERLAP, PollWindowTest::second)).isEqualTo(new PollWindow(SINCE.plusSeconds(60), List.of(), null));
    }

    private static Instant minute(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MINUTES);
    }

    private static Instant second(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS);
    }
}
