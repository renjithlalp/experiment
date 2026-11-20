package com.example.experiment.service;

import com.example.experiment.Json;
import com.example.experiment.domain.ExperimentConfig;
import com.example.experiment.domain.FieldChange;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lists the fields that differ between two configurations, as dotted paths such as {@code split.testPercent}.
 * Lists (fallback order, product codes) are compared as a whole.
 */
public final class ConfigDiff {

    private ConfigDiff() {
    }

    public static List<FieldChange> between(ExperimentConfig before, ExperimentConfig after) {
        Map<String, String> a = flatten(before);
        Map<String, String> b = flatten(after);
        Set<String> fields = new LinkedHashSet<>(a.keySet());
        fields.addAll(b.keySet());
        List<FieldChange> changes = new ArrayList<>();
        for (String field : fields) {
            String from = a.get(field);
            String to = b.get(field);
            if (from == null ? to != null : !from.equals(to)) {
                changes.add(new FieldChange(field, from, to));
            }
        }
        return changes;
    }

    private static Map<String, String> flatten(ExperimentConfig config) {
        Map<String, String> out = new LinkedHashMap<>();
        if (config != null) {
            flatten("", Json.MAPPER.valueToTree(config), out);
        }
        return out;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else if (node.isArray()) {
            if (!node.isEmpty()) {
                out.put(prefix, node.toString());
            }
        } else if (node.isBigDecimal()) {
            out.put(prefix, node.decimalValue().toPlainString());
        } else {
            out.put(prefix, node.asText());
        }
    }
}
