package pe.pucp.paqrap.app.web;

/** Utilidades mínimas para escribir JSON sin librerías. */
public final class Json {
    private Json() {
    }

    public static String str(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    public static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
