package com.antimatterzone.apconnector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature());
    static String canonical(JsonNode node) throws Exception {
        if (node.isObject()) {
            List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add); names.sort(String::compareTo);
            List<String> fields = new ArrayList<>();
            for (String name : names) fields.add(quote(name) + ":" + canonical(node.get(name)));
            return "{" + String.join(",", fields) + "}";
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>(); for (JsonNode value : node) values.add(canonical(value));
            return "[" + String.join(",", values) + "]";
        }
        return node.isTextual() ? quote(node.asText()) : MAPPER.writeValueAsString(node);
    }
    // Match Python json.dumps(ensure_ascii=True, separators=(',', ':'), sort_keys=True).
    private static String quote(String value) {
        StringBuilder result=new StringBuilder("\"");
        for(char character:value.toCharArray()) {
            switch(character) {
                case '"' -> result.append("\\\""); case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b"); case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r"); case '\t' -> result.append("\\t");
                default -> {if(character<32 || character>127) result.append(String.format(Locale.ROOT,"\\u%04x",(int)character));else result.append(character);}
            }
        }
        return result.append('"').toString();
    }
    static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private Json() { }
}
