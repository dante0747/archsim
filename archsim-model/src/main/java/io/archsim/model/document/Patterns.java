package io.archsim.model.document;

/** The regular expressions the document's ids and names match, spelled as the Python schema spells them. */
final class Patterns {

    /** A node, edge, fault or scenario id: letters, digits, {@code _ . -}, starting with a letter or digit. */
    static final String ID = "^[A-Za-z0-9][A-Za-z0-9_.\\-]{0,63}$";

    /** A dotted path inside a config or the workload, such as {@code serviceTimeMs.median}. */
    static final String OVERRIDE_PATH = "^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$";

    /** A price provider or region, such as {@code aws} or {@code us-east-1}. */
    static final String PRICE_NAME = "^[a-z0-9-]{1,40}$";

    /** An instance family, such as {@code m7g}. */
    static final String FAMILY = "^[A-Za-z0-9._-]{1,40}$";

    /** A date, {@code YYYY-MM-DD}. */
    static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";

    /** A SHA-256 hash in lower-case hex. */
    static final String SHA256 = "^[0-9a-f]{64}$";

    private Patterns() {}
}
