package com.cadbbox.parser.step;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes STEP string escapes per ISO 10303-21:
 * <ul>
 *   <li>{@code \X2\HHHH...\X0\} — UTF-16-BE hex code points (Creo's way of
 *       embedding Chinese & other non-ASCII names, e.g. {@code \X2\4E3B673A\X0\}
 *       → {@code 主机}).</li>
 *   <li>{@code \X\HH} — single ISO 10646 code point (rare).</li>
 *   <li>{@code \S\NN} — ASCII + 128 (legacy, rare).</li>
 * </ul>
 *
 * <p>Recon on GMC2550WRS: names like {@code GMC2550WRS\X2\4E3B673A\X0\} decode to
 * {@code GMC2550WRS主机}, and {@code GB\X2\FF0F\X0\T6170...} → {@code GB／T6170...}.
 */
public final class StepNameCodec {

    private static final Pattern X2 = Pattern.compile("\\\\X2\\\\([0-9A-Fa-f]+)\\\\X0\\\\");
    private static final Pattern X_SINGLE = Pattern.compile("\\\\X\\\\([0-9A-Fa-f]{2})");

    private StepNameCodec() {}

    /** Decode all {@code \X2\} and {@code \X\} escapes; pass through ASCII as-is. */
    public static String decode(String s) {
        if (s == null || s.isEmpty()) return s;
        // \X2\...\X0\ → UTF-16-BE
        Matcher m = X2.matcher(s);
        StringBuffer sb = new StringBuffer(s.length());
        while (m.find()) {
            String hex = m.group(1);
            // Hex length must be even; decode as UTF-16-BE pairs.
            if (hex.length() % 2 != 0) { m.appendReplacement(sb, Matcher.quoteReplacement(m.group())); continue; }
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            String dec = new String(bytes, java.nio.charset.StandardCharsets.UTF_16BE);
            m.appendReplacement(sb, Matcher.quoteReplacement(dec));
        }
        m.appendTail(sb);
        // \X\HH → single byte (Latin-1 of that code point)
        String result = sb.toString();
        Matcher mx = X_SINGLE.matcher(result);
        StringBuffer sb2 = new StringBuffer();
        while (mx.find()) {
            int cp = Integer.parseInt(mx.group(1), 16);
            mx.appendReplacement(sb2, Matcher.quoteReplacement(String.valueOf((char) cp)));
        }
        mx.appendTail(sb2);
        return sb2.toString();
    }
}
