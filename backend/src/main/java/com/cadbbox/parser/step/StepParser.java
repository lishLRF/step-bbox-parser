package com.cadbbox.parser.step;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streaming parser for the {@code DATA} section of an ISO-10303-21 (STEP) file.
 *
 * <p><b>Why a custom parser.</b> Real-world exports (Creo in particular) put
 * nearly every parameter on its own line, so a single entity spans many input
 * lines. A line-based regex misses most of them. This parser instead tracks
 * parenthesis depth and accumulates complete entities, then tokenizes each
 * entity's argument list into typed tokens ({@link StepEntity.Ref},
 * {@link StepEntity.InlineList}, String).
 *
 * <p><b>Memory.</b> Entities are indexed in a {@link LinkedHashMap} so they can
 * be looked up by id. For the 173 MB GMC2550WRS sample (2.87 M entities) this is
 * the dominant heap consumer and is targeted for optimization in Slice 10; for
 * Slice 1's single-part sample it is trivial. Geometry accumulation (the 866 k
 * {@code CARTESIAN_POINT}s) is done incrementally elsewhere, not here.
 *
 * <p>The parser is permissive: unknown entity types, the {@code *} placeholder,
 * doubled trailing parens from Creo, and inline lists are all handled.
 */
@Component
public class StepParser {

    private static final Pattern ENTITY_START =
            Pattern.compile("#(\\d+)\\s*=\\s*([A-Z0-9_]+)\\s*\\(");

    /**
     * Pattern for STEP "complex entities" (supertype conjunction):
     * {@code #746=(TYPE1(...)TYPE2(...)TYPE3(...));}. Creo uses these for
     * REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION. We capture the id and
     * the first type, then store the full inner content so callers can search
     * for sub-type patterns.
     */
    private static final Pattern COMPLEX_ENTITY_START =
            Pattern.compile("#(\\d+)\\s*=\\s*\\(\\s*([A-Z0-9_]+)\\s*\\(");

    /**
     * Parse a STEP stream into an indexed bag of entities plus a few facts
     * lifted from the {@code HEADER} section (source CAD system, schema, unit).
     */
    public ParsedStepFile parse(InputStream in) throws IOException {
        Map<Integer, StepEntity> entities = new LinkedHashMap<>();
        List<String> schemas = new ArrayList<>();
        String sourceCadSystem = "";
        String unit = "MILLIMETER"; // default assumption; refined from HEADER

        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1))) {
            Section section = Section.PRE;
            StringBuilder buf = new StringBuilder(64);
            int depth = 0;
            Integer curId = null;
            String curType = null;

            String line;
            while ((line = reader.readLine()) != null) {
                switch (section) {
                    case PRE -> {
                        if ("DATA;".equals(line.trim())) {
                            section = Section.DATA;
                        } else if (line.startsWith("FILE_NAME")) {
                            sourceCadSystem = extractFileNameField(line);
                        } else if (line.startsWith("FILE_SCHEMA")) {
                            collectSchema(line, schemas);
                        }
                    }
                    case DATA -> {
                        if ("ENDSEC;".equals(line.trim())) {
                            section = Section.POST;
                            continue;
                        }
                        // Accumulate the current entity across lines, flushing only
                        // when a line ends the entity: it carries the trailing `;`
                        // AND the accumulated paren depth is back to zero. This
                        // distinguishes a multi-line entity whose first line has
                        // balanced parens but no terminator from a true one-liner.
                        if (curId == null) {
                            Matcher m = ENTITY_START.matcher(line);
                            Matcher cm = COMPLEX_ENTITY_START.matcher(line);
                            if (cm.find()) {
                                // Complex entity: #id=(TYPE1(...)TYPE2(...)...);
                                curId = Integer.parseInt(cm.group(1));
                                curType = cm.group(2);
                                // Keep the full inner content (starting from first TYPE)
                                int eqParen = line.indexOf("=(");
                                String after = line.substring(eqParen + 2); // skip "=( "
                                buf.append(after);
                                depth = parenDelta(after);
                                if (depth <= 0 && after.trim().endsWith(";")) {
                                    flush(entities, curId, curType, buf);
                                    curId = null; buf.setLength(0); depth = 0;
                                }
                            } else if (m.find()) {
                                curId = Integer.parseInt(m.group(1));
                                curType = m.group(2);
                                String after = line.substring(m.end());
                                buf.append(after);
                                depth = parenDelta(after);
                                if (depth <= 0 && after.trim().endsWith(";")) {
                                    flush(entities, curId, curType, buf);
                                    curId = null; buf.setLength(0); depth = 0;
                                }
                            }
                        } else {
                            buf.append('\n').append(line);
                            depth += parenDelta(line);
                            if (depth <= 0 && line.trim().endsWith(";")) {
                                flush(entities, curId, curType, buf);
                                curId = null; buf.setLength(0); depth = 0;
                            }
                        }
                    }
                    default -> { /* POST — ignore */ }
                }
            }
        }
        return new ParsedStepFile(entities, schemas, sourceCadSystem, unit);
    }

    private void flush(Map<Integer, StepEntity> sink, int id, String type, StringBuilder raw) {
        String args = trimEntityTerminator(raw.toString());
        sink.put(id, new StepEntity(id, type, tokenize(args)));
    }

    /**
     * Strip the trailing {@code ;} and the single {@code )} that closes the
     * argument list. The opening {@code (} was already consumed by the start
     * regex, so the buffer holds the raw argument text <em>inside</em> the
     * outer parens, possibly followed by Creo's extra {@code )} characters.
     *
     * <p>The matching close of the argument list is the {@code )} that takes
     * the running depth from 0 to -1 (because we started at depth 0 inside the
     * outer group). Cut there; drop everything after.
     */
    static String trimEntityTerminator(String s) {
        String t = s.strip();
        if (t.endsWith(";")) t = t.substring(0, t.length() - 1).strip();
        int d = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '(') d++;
            else if (c == ')') {
                d--;
                if (d < 0) {
                    // i is the matching close of the outer argument list; cut here.
                    return t.substring(0, i);
                }
            }
        }
        // No matching close — return as-is and let the tokenizer cope.
        return t;
    }

    /** Tokenize a comma-separated argument list, respecting nested parens & quoted strings. */
    static List<Object> tokenize(String args) {
        List<Object> out = new ArrayList<>();
        int i = 0, n = args.length();
        while (i < n) {
            // skip whitespace and leading commas
            while (i < n && (Character.isWhitespace(args.charAt(i)) || args.charAt(i) == ',')) i++;
            if (i >= n) break;
            char c = args.charAt(i);
            if (c == '(') {
                // inline list — find matching close
                int d = 0, start = i;
                do {
                    if (args.charAt(i) == '(') d++;
                    else if (args.charAt(i) == ')') d--;
                    i++;
                } while (i < n && d > 0);
                String inner = args.substring(start + 1, i - 1);
                out.add(new StepEntity.InlineList(tokenize(inner)));
            } else if (c == '\'') {
                // quoted string — handle '' escape
                int start = i; i++;
                while (i < n) {
                    if (args.charAt(i) == '\'') {
                        if (i + 1 < n && args.charAt(i + 1) == '\'') { i += 2; continue; }
                        i++; break;
                    }
                    i++;
                }
                out.add(args.substring(start, Math.min(i, n)));
            } else if (c == '#') {
                int start = i; i++;
                while (i < n && Character.isDigit(args.charAt(i))) i++;
                out.add(new StepEntity.Ref(Integer.parseInt(args.substring(start + 1, i))));
            } else if (c == '*') {
                out.add("*");
                i++;
            } else if (c == '$') {
                out.add("$");
                i++;
            } else {
                // enum (e.g. .T.) or number or identifier — read until comma/paren at depth 0
                int start = i;
                while (i < n && args.charAt(i) != ',' && args.charAt(i) != '(' && args.charAt(i) != ')') i++;
                // Defensive: never emit a zero-length token (would loop forever
                // since the outer loop only advances past whitespace/commas).
                if (i == start) i++;
                out.add(args.substring(start, i).strip());
            }
        }
        return out;
    }

    private static int parenDelta(String s) {
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') d++;
            else if (c == ')') d--;
        }
        return d;
    }

    private static String extractFileNameField(String line) {
        // FILE_NAME('name','date',('author'),('org'),'prog','prog','');
        // the 5th field (index 4) is the originating program/CAD system.
        List<String> strs = splitTopLevelStrings(line);
        return strs.size() > 4 ? strs.get(4) : (strs.isEmpty() ? "" : strs.get(0));
    }

    private static void collectSchema(String line, List<String> out) {
        // FILE_SCHEMA(('CONFIG_CONTROL_DESIGN','SHAPE_APPEARANCE_LAYERS_GROUPS'));
        for (String s : splitTopLevelStrings(line)) out.add(s);
    }

    private static List<String> splitTopLevelStrings(String line) {
        List<String> res = new ArrayList<>();
        int i = 0, n = line.length();
        while (i < n) {
            while (i < n && line.charAt(i) != '\'') i++;
            if (i >= n) break;
            i++; // past opening quote
            int start = i;
            while (i < n) {
                if (line.charAt(i) == '\'') {
                    if (i + 1 < n && line.charAt(i + 1) == '\'') { i += 2; continue; }
                    break;
                }
                i++;
            }
            res.add(line.substring(start, Math.min(i, n)));
            if (i < n) i++; // past closing quote
        }
        return res;
    }

    private enum Section { PRE, DATA, POST }

    /** Result of parsing: indexed entities + header facts. */
    public record ParsedStepFile(
            Map<Integer, StepEntity> entities,
            List<String> schemas,
            String sourceCadSystem,
            String unit
    ) {}
}
