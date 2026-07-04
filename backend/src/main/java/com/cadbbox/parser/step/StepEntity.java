package com.cadbbox.parser.step;

import java.util.List;

/**
 * One parsed STEP entity: an id (the {@code #nnn} reference) plus its
 * keyword and the raw argument list.
 *
 * <p>Arguments are stored as a list of tokens in source order. Each token is
 * either:
 * <ul>
 *   <li>a reference to another entity — {@code #nnn} — wrapped in {@link Ref};</li>
 *   <li>an inline list — {@code (a,b,c)} or {@code (#1,#2)} — wrapped in {@link InlineList};</li>
 *   <li>any other literal (string, number, enum, {@code *} placeholder, {@code .T.}) as String.</li>
 * </ul>
 *
 * <p>The parser is intentionally permissive: it does not validate against any
 * STEP schema. Callers extract what they need by position/type.
 */
public record StepEntity(int id, String type, List<Object> args) {

    /** A reference to another entity by id. */
    public record Ref(int id) {}

    /** An inline list of tokens, e.g. {@code (#1,#2,#3)} or {@code (1.0,2.0,3.0)}. */
    public record InlineList(List<Object> items) {}

    /** Convenience: the n-th arg (0-based) or null if absent. */
    public Object arg(int i) {
        return (i >= 0 && i < args.size()) ? args.get(i) : null;
    }

    /** The n-th arg as a Ref, or null if it isn't one. */
    public Ref refAt(int i) {
        Object a = arg(i);
        return (a instanceof Ref r) ? r : null;
    }

    /** The n-th arg as an InlineList, or null if it isn't one. */
    public InlineList listAt(int i) {
        Object a = arg(i);
        return (a instanceof InlineList l) ? l : null;
    }

    /** The n-th arg as a String (unquoted), or null. */
    public String stringAt(int i) {
        Object a = arg(i);
        return (a instanceof String s) ? unquote(s) : null;
    }

    /** The n-th arg as a double, or NaN if not parseable. */
    public double doubleAt(int i) {
        Object a = arg(i);
        if (a instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'') {
            return t.substring(1, t.length() - 1).replace("''", "'");
        }
        return t;
    }
}
