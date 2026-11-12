package io.archsim.app.web;

import java.util.List;

import org.springframework.core.convert.converter.Converter;

/**
 * Binds one text to a {@code List<String>} as one value, whole and as given, as FastAPI
 * read a query value: endpoints take query parameters as {@code List<String>} to read the
 * last of a repeated one ({@link QueryParameters}). Spring's own conversion splits the
 * text at its commas and trims the pieces, and makes an empty text an empty list, so
 * {@code ?name=Acme, Inc} named a project {@code Inc} and {@code ?limit=} read as not
 * given (the default 50), where FastAPI refused it. A parameter given more than once is a
 * list already and does not come here.
 *
 * <p>
 * It is in Spring MVC's conversion service, so it binds every text to a
 * {@code List<String>} (or a raw {@code List}), whatever the source: a header, a path
 * variable or a cookie as well as a query parameter. A header that joins its values with
 * commas would come as one value; no endpoint takes one as a list today. A list of
 * anything else converts as Spring converts it.
 */
final class WholeQueryValue implements Converter<String, List<String>> {

	@Override
	public List<String> convert(String value) {
		return List.of(value);
	}

}
