package io.archsim.tools.prices;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/**
 * Where the refresh gets a provider's price lists from: their public endpoints, or a
 * test's pages.
 */
interface PriceDownloads {

	/**
	 * The body at an address.
	 * @param address the address
	 * @return its body
	 * @throws IOException when it cannot be fetched, or the answer is not a success
	 */
	byte[] bytes(URI address) throws IOException;

	/**
	 * The body at an address, kept in a cache file: fetched into it once, and the file
	 * reused while it is there and not empty.
	 * @param address the address
	 * @param cache the cache file
	 * @return the cache file
	 * @throws IOException when it cannot be fetched or kept
	 */
	Path file(URI address, Path cache) throws IOException;

}
