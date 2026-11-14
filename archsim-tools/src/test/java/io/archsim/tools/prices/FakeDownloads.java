package io.archsim.tools.prices;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A price API's pages by their address, and the addresses asked for; any other address is
 * not found.
 *
 * @param pages each page's text by its address
 * @param asked the addresses asked for, in order
 */
record FakeDownloads(Map<String, String> pages, List<String> asked) implements PriceDownloads {

	FakeDownloads(Map<String, String> pages) {
		this(Map.copyOf(pages), new ArrayList<>());
	}

	@Override
	public byte[] bytes(URI address) throws IOException {
		this.asked.add(address.toString());
		String page = this.pages.get(address.toString());
		if (page == null) {
			throw new IOException("no page at " + address);
		}
		return page.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public Path file(URI address, Path cache) throws IOException {
		Files.write(cache, bytes(address));
		return cache;
	}

}
