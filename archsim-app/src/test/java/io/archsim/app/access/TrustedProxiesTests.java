package io.archsim.app.access;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The proxies believed about who is asking. */
class TrustedProxiesTests {

	private static final Ip WEB = IpText.parse("172.18.0.4").orElseThrow();

	private final List<String> lookedUp = new ArrayList<>();

	private final MovableClock clock = new MovableClock();

	@Test
	void trust_peerInATrustedNetwork_isTrusted() {
		assertThat(proxies("172.16.0.0/12").trust(WEB)).isTrue();
	}

	@Test
	void trust_loopbackAsJavaWritesIt_isThisMachine() {
		assertThat(proxies("127.0.0.1", "::1").trust(IpText.parse("0:0:0:0:0:0:0:1").orElseThrow())).isTrue();
	}

	@Test
	void trust_hostNameWithThePeersAddress_isTrusted() {
		assertThat(proxies("web").trust(WEB)).isTrue();
	}

	@Test
	void trust_otherPeer_isNotTrusted() {
		assertThat(proxies("127.0.0.1").trust(WEB)).isFalse();
	}

	@Test
	void trust_withinAMinute_looksAHostNameUpOnce() {
		TrustedProxies proxies = proxies("web");
		proxies.trust(WEB);
		this.clock.advance(Duration.ofSeconds(59));

		proxies.trust(WEB);

		assertThat(this.lookedUp).containsExactly("web");
	}

	@Test
	void trust_aMinuteLater_looksAHostNameUpAgain() {
		TrustedProxies proxies = proxies("web");
		proxies.trust(WEB);
		this.clock.advance(Duration.ofMinutes(1));

		proxies.trust(WEB);

		assertThat(this.lookedUp).containsExactly("web", "web");
	}

	@Test
	void trust_blankEntry_isNeverLookedUp() {
		TrustedProxies proxies = proxies(" ", "10.0.0.5");

		proxies.trust(WEB);

		assertThat(this.lookedUp).isEmpty();
	}

	private TrustedProxies proxies(String... entries) {
		return new TrustedProxies(List.of(entries), this.clock, (host) -> {
			this.lookedUp.add(host);
			return Set.of(WEB.address());
		});
	}

	/** A clock a test moves on. */
	private static final class MovableClock extends Clock {

		private Instant now = Instant.parse("2026-11-03T00:00:00Z");

		void advance(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}

	}

}
