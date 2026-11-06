package com.flowtivo.tinfirm.shared.net;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The bytes of request bodies a process holds at once, within its cap. */
class InflightBytesTest {

    @Test
    void aProcessHoldsAtMostItsCapOfRequestBytes() {
        InflightBytes inflight = new InflightBytes(100);
        InflightBytes.Share first = inflight.share();
        assertThat(first.add(60)).isTrue();
        InflightBytes.Share second = inflight.share();
        // Past the cap: nothing taken.
        assertThat(second.add(41)).isFalse();
        assertThat(inflight.held()).isEqualTo(60);
        assertThat(second.add(40)).isTrue();
        assertThat(inflight.held()).isEqualTo(100);
        assertThat(second.add(1)).isFalse();
        // A body written anew is kept, the rest given back.
        first.keep(30);
        assertThat(inflight.held()).isEqualTo(70);
        first.keep(50);
        assertThat(inflight.held()).isEqualTo(70);
        first.close();
        second.close();
        second.close();
        assertThat(inflight.held()).isZero();
        assertThat(inflight.share().add(100)).isTrue();
    }

    @Test
    void aShareGivesBackPartOfWhatItHoldsAndNeverMore() {
        InflightBytes inflight = new InflightBytes(100);
        InflightBytes.Share share = inflight.share();
        assertThat(share.add(70)).isTrue();
        share.release(30);
        assertThat(inflight.held()).isEqualTo(40);
        // Never more than the share holds, and never a negative amount.
        share.release(-5);
        assertThat(inflight.held()).isEqualTo(40);
        share.release(1000);
        assertThat(inflight.held()).isZero();
        InflightBytes.Share other = inflight.share();
        assertThat(other.add(100)).isTrue();
        share.close();
        assertThat(inflight.held()).as("the first share holds nothing more to give back").isEqualTo(100);
        other.close();
        assertThat(inflight.held()).isZero();
    }
}
