package io.konzert.core.intake;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One condition on a CloudEvent, or a group of them.
 *
 * Structured on purpose (ADR-0007): field / op / value, grouped by all / any /
 * not. Not an expression language. That choice is what lets a panel render a
 * filter as rows of dropdowns — an operator builds one by clicking, not by
 * learning a syntax — and it is why a rule cannot loop, throw, or be turned
 * into something executable.
 *
 * A predicate NEVER throws. A missing field, a wrong type, a broken regex: all
 * of them evaluate to false. An agent's filter failing closed is a quiet agent;
 * a filter that throws is an agent that stops consuming.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Predicate {

    /** Dotted path into the CloudEvent: "type", "data.namespace", "data.to". */
    public String field;
    /** eq neq in notIn contains startsWith endsWith matches exists gt gte lt lte */
    public String op;
    /** Scalar, or array for in/notIn. */
    public JsonNode value;

    public List<Predicate> all;
    public List<Predicate> any;
    public Predicate not;

    /** Regexes come from a config file a human edits; keep them cheap and bounded. */
    private static final int MAX_REGEX_INPUT = 4096;

    public boolean test(JsonNode event) {
        if (event == null) {
            return false;
        }
        if (all != null && !all.isEmpty()) {
            for (Predicate p : all) {
                if (!p.test(event)) {
                    return false;
                }
            }
            return true;
        }
        if (any != null && !any.isEmpty()) {
            for (Predicate p : any) {
                if (p.test(event)) {
                    return true;
                }
            }
            return false;
        }
        if (not != null) {
            return !not.test(event);
        }
        return testLeaf(event);
    }

    private boolean testLeaf(JsonNode event) {
        if (field == null || op == null) {
            return false;
        }
        JsonNode actual = resolve(event, field);
        String operator = op.trim();

        if ("exists".equals(operator)) {
            return actual != null && !actual.isNull();
        }
        if (actual == null || actual.isNull()) {
            // Every other operator needs something to compare against.
            return false;
        }

        return switch (operator) {
            case "eq" -> equalsValue(actual);
            case "neq" -> !equalsValue(actual);
            case "in" -> anyOfValueMatches(actual);
            case "notIn" -> !anyOfValueMatches(actual);
            case "contains" -> contains(actual);
            case "startsWith" -> asText(actual).startsWith(valueText());
            case "endsWith" -> asText(actual).endsWith(valueText());
            case "matches" -> matches(asText(actual));
            case "gt", "gte", "lt", "lte" -> compare(actual, operator);
            default -> false;
        };
    }

    /**
     * Resolves a dotted path. Array elements are not addressable by index on
     * purpose: filters address facts ("data.namespace"), not positions.
     */
    static JsonNode resolve(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            if (node == null || !node.isObject()) {
                return null;
            }
            node = node.get(part);
        }
        return node;
    }

    private boolean equalsValue(JsonNode actual) {
        if (value == null) {
            return false;
        }
        if (actual.isNumber() && value.isNumber()) {
            return actual.asDouble() == value.asDouble();
        }
        if (actual.isBoolean() && value.isBoolean()) {
            return actual.asBoolean() == value.asBoolean();
        }
        return asText(actual).equals(valueText());
    }

    /** in/notIn: the configured value is a list; the event field may itself be a list. */
    private boolean anyOfValueMatches(JsonNode actual) {
        if (value == null) {
            return false;
        }
        List<String> candidates = new ArrayList<>();
        if (actual.isArray()) {
            actual.forEach(n -> candidates.add(asText(n)));
        } else {
            candidates.add(asText(actual));
        }
        if (value.isArray()) {
            for (JsonNode v : value) {
                if (candidates.contains(asText(v))) {
                    return true;
                }
            }
            return false;
        }
        return candidates.contains(valueText());
    }

    /** Membership for arrays, substring for strings — whichever the field is. */
    private boolean contains(JsonNode actual) {
        if (actual.isArray()) {
            for (JsonNode n : actual) {
                if (asText(n).equals(valueText())) {
                    return true;
                }
            }
            return false;
        }
        return asText(actual).contains(valueText());
    }

    private boolean matches(String actual) {
        if (actual.length() > MAX_REGEX_INPUT) {
            return false;
        }
        try {
            Matcher m = Pattern.compile(valueText()).matcher(actual);
            return m.find();
        } catch (PatternSyntaxException e) {
            // A typo in a rule must not take the agent's ear offline.
            return false;
        }
    }

    private boolean compare(JsonNode actual, String operator) {
        Double a = asNumber(actual);
        Double b = value == null ? null : asNumber(value);
        if (a == null || b == null) {
            return false;
        }
        return switch (operator) {
            case "gt" -> a > b;
            case "gte" -> a >= b;
            case "lt" -> a < b;
            case "lte" -> a <= b;
            default -> false;
        };
    }

    /** Numbers, numeric strings, and ISO timestamps all compare as numbers. */
    private static Double asNumber(JsonNode n) {
        if (n.isNumber()) {
            return n.asDouble();
        }
        String s = n.asText("");
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ignored) {
            try {
                return (double) java.time.Instant.parse(s).toEpochMilli();
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static String asText(JsonNode n) {
        return n.isValueNode() ? n.asText() : n.toString();
    }

    private String valueText() {
        return value == null ? "" : asText(value);
    }

    /** Human-readable form, used by the funnel to say which rule decided. */
    @Override
    public String toString() {
        if (all != null) {
            return "all" + all;
        }
        if (any != null) {
            return "any" + any;
        }
        if (not != null) {
            return "not(" + not + ")";
        }
        return field + " " + op + " " + valueText();
    }
}
