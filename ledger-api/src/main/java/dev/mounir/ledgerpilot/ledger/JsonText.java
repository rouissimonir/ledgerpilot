package dev.mounir.ledgerpilot.ledger;

public final class JsonText {

    private JsonText() {
    }

    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u");
                        out.append(Character.forDigit((c >> 12) & 0xF, 16));
                        out.append(Character.forDigit((c >> 8) & 0xF, 16));
                        out.append(Character.forDigit((c >> 4) & 0xF, 16));
                        out.append(Character.forDigit(c & 0xF, 16));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
