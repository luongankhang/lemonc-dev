package site.ilemon.diagnostic;

/** Stable public diagnostic identifiers grouped by compiler phase. */
public final class DiagnosticCodes {
    private DiagnosticCodes() {
    }

    public static final String LEX_INVALID_INPUT = "E0001";

    public static final String PARSE_EXPECTED_TOKEN = "E1001";
    public static final String PARSE_INVALID_CONSTRUCT = "E1002";
    public static final String PARSE_INVALID_EXPRESSION = "E1003";

    public static final String SEM_UNKNOWN_VARIABLE = "E2001";
    public static final String SEM_UNKNOWN_FUNCTION = "E2002";
    public static final String SEM_DUPLICATE_DECLARATION = "E2003";
    public static final String SEM_INVALID_SYMBOL_USAGE = "E2004";
    public static final String SEM_INVALID_SCOPE = "E2005";
    public static final String SEM_GENERAL = "E2099";

    public static final String TYPE_ASSIGNMENT = "E3001";
    public static final String TYPE_RETURN = "E3002";
    public static final String TYPE_ARGUMENT = "E3003";
    public static final String TYPE_OPERATOR = "E3004";
    public static final String TYPE_CONDITION = "E3005";
    public static final String TYPE_FORMAT = "E3006";
    public static final String TYPE_INDEX = "E3007";
    public static final String TYPE_BYTE_RANGE = "E3008";
    public static final String TYPE_SHORT_RANGE = "E3009";

    public static final String MODULE_NOT_FOUND = "E4001";
    public static final String GENERIC_ERROR = "E5001";
    public static final String FFI_ERROR = "E6001";
    public static final String BACKEND_ERROR = "E7001";

    public static final String INTERNAL_COMPILER_ERROR = "E9001";

    public static boolean isValid(String code) {
        return code != null && code.matches("E[0-9]{4}");
    }
}
