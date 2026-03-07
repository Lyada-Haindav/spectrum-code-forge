package com.spectrumforge;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MiniJson {
    private MiniJson() {
    }

    static Object parse(String input) {
        Parser parser = new Parser(input);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isEnd()) {
            throw new AppException(400, "Invalid JSON payload.");
        }
        return value;
    }

    static String stringify(Object value) {
        StringBuilder builder = new StringBuilder();
        writeJson(builder, value);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value, String message) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new AppException(400, message);
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return (List<Object>) list;
    }

    private static void writeJson(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
            return;
        }

        if (value instanceof String text) {
            builder.append('"');
            for (int index = 0; index < text.length(); index++) {
                char ch = text.charAt(index);
                switch (ch) {
                    case '\\' -> builder.append("\\\\");
                    case '"' -> builder.append("\\\"");
                    case '\b' -> builder.append("\\b");
                    case '\f' -> builder.append("\\f");
                    case '\n' -> builder.append("\\n");
                    case '\r' -> builder.append("\\r");
                    case '\t' -> builder.append("\\t");
                    default -> {
                        if (ch < 32) {
                            builder.append(String.format("\\u%04x", (int) ch));
                        } else {
                            builder.append(ch);
                        }
                    }
                }
            }
            builder.append('"');
            return;
        }

        if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
            return;
        }

        if (value instanceof Map<?, ?> map) {
            builder.append('{');
            Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                writeJson(builder, String.valueOf(entry.getKey()));
                builder.append(':');
                writeJson(builder, entry.getValue());
                if (iterator.hasNext()) {
                    builder.append(',');
                }
            }
            builder.append('}');
            return;
        }

        if (value instanceof Iterable<?> iterable) {
            builder.append('[');
            Iterator<?> iterator = iterable.iterator();
            while (iterator.hasNext()) {
                writeJson(builder, iterator.next());
                if (iterator.hasNext()) {
                    builder.append(',');
                }
            }
            builder.append(']');
            return;
        }

        throw new AppException(500, "Unable to serialize JSON.");
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input == null ? "" : input;
        }

        private Object parseValue() {
            skipWhitespace();
            if (isEnd()) {
                throw new AppException(400, "Invalid JSON payload.");
            }

            char current = currentChar();
            return switch (current) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseTrue();
                case 'f' -> parseFalse();
                case 'n' -> parseNull();
                default -> {
                    if (current == '-' || Character.isDigit(current)) {
                        yield parseNumber();
                    }
                    throw new AppException(400, "Invalid JSON payload.");
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            skipWhitespace();

            Map<String, Object> object = new LinkedHashMap<>();
            if (peek('}')) {
                index++;
                return object;
            }

            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                object.put(key, value);
                skipWhitespace();

                if (peek('}')) {
                    index++;
                    return object;
                }

                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            skipWhitespace();

            List<Object> array = new ArrayList<>();
            if (peek(']')) {
                index++;
                return array;
            }

            while (true) {
                array.add(parseValue());
                skipWhitespace();

                if (peek(']')) {
                    index++;
                    return array;
                }

                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();

            while (!isEnd()) {
                char current = currentChar();
                index++;

                if (current == '"') {
                    return builder.toString();
                }

                if (current == '\\') {
                    if (isEnd()) {
                        throw new AppException(400, "Invalid JSON payload.");
                    }
                    char escape = currentChar();
                    index++;
                    switch (escape) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case '/' -> builder.append('/');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> builder.append(parseUnicode());
                        default -> throw new AppException(400, "Invalid JSON payload.");
                    }
                    continue;
                }

                builder.append(current);
            }

            throw new AppException(400, "Invalid JSON payload.");
        }

        private char parseUnicode() {
            if (index + 4 > input.length()) {
                throw new AppException(400, "Invalid JSON payload.");
            }
            String hex = input.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException error) {
                throw new AppException(400, "Invalid JSON payload.");
            }
        }

        private Boolean parseTrue() {
            expectLiteral("true");
            return Boolean.TRUE;
        }

        private Boolean parseFalse() {
            expectLiteral("false");
            return Boolean.FALSE;
        }

        private Object parseNull() {
            expectLiteral("null");
            return null;
        }

        private Number parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }

            if (peek('0')) {
                index++;
            } else {
                consumeDigits();
            }

            if (peek('.')) {
                index++;
                consumeDigits();
            }

            if (!isEnd() && (currentChar() == 'e' || currentChar() == 'E')) {
                index++;
                if (!isEnd() && (currentChar() == '+' || currentChar() == '-')) {
                    index++;
                }
                consumeDigits();
            }

            String token = input.substring(start, index);
            try {
                return new BigDecimal(token);
            } catch (NumberFormatException error) {
                throw new AppException(400, "Invalid JSON payload.");
            }
        }

        private void consumeDigits() {
            if (isEnd() || !Character.isDigit(currentChar())) {
                throw new AppException(400, "Invalid JSON payload.");
            }

            while (!isEnd() && Character.isDigit(currentChar())) {
                index++;
            }
        }

        private void expectLiteral(String literal) {
            if (input.regionMatches(index, literal, 0, literal.length())) {
                index += literal.length();
                return;
            }
            throw new AppException(400, "Invalid JSON payload.");
        }

        private void expect(char expected) {
            skipWhitespace();
            if (isEnd() || currentChar() != expected) {
                throw new AppException(400, "Invalid JSON payload.");
            }
            index++;
        }

        private boolean peek(char expected) {
            return !isEnd() && currentChar() == expected;
        }

        private char currentChar() {
            return input.charAt(index);
        }

        private boolean isEnd() {
            return index >= input.length();
        }

        private void skipWhitespace() {
            while (!isEnd() && Character.isWhitespace(currentChar())) {
                index++;
            }
        }
    }
}
