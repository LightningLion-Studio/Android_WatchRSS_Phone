package watchrss.build;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class ReleaseConfiguration {
    public static final String BACKEND_URL = "WATCHRSS_BACKEND_BASE_URL";
    public static final String ANON_KEY = "WATCHRSS_SUPABASE_ANON_KEY";
    public static final String APP_ACCESS_KEY = "WATCHRSS_APP_ACCESS_PUBLIC_KEY";
    public static final String AI_DISCLOSURE_ASSET = "ai-disclosure-default.json";

    private static final String PLACEHOLDER = "__FILL_BEFORE_RELEASE__";
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final List<String> REQUIRED_DISCLOSURE_TEXT = List.of("displayText", "modelName");
    private static final List<String> OPTIONAL_DISCLOSURE_TEXT =
            List.of("providerName", "filingNumber", "launchNumber");

    private ReleaseConfiguration() {}

    @SafeVarargs
    public static String firstNonBlank(Supplier<String>... sources) {
        for (Supplier<String> source : sources) {
            String value = source.get();
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    public static List<String> invalidFields(String backendUrl, String anonKey, String appAccessKey) {
        List<String> fields = new ArrayList<>();
        if (!isHttpsBaseUrl(backendUrl)) {
            fields.add(BACKEND_URL);
        }
        if (anonKey.isBlank()) {
            fields.add(ANON_KEY);
        }
        if (appAccessKey.isBlank()) {
            fields.add(APP_ACCESS_KEY);
        }
        return fields;
    }

    public static List<String> invalidAiDisclosureFields(String json) {
        if (json == null || json.isBlank()) {
            return List.of(AI_DISCLOSURE_ASSET);
        }
        JsonElement value;
        try {
            value = JSON.fromJson(json, JsonElement.class);
        } catch (JsonParseException invalidJson) {
            return List.of(AI_DISCLOSURE_ASSET);
        }
        if (value == null || !value.isJsonObject()) {
            return List.of(AI_DISCLOSURE_ASSET);
        }
        var snapshot = value.getAsJsonObject();
        List<String> fields = new ArrayList<>();
        for (String field : List.of("revision", "updatedAt")) {
            if (!isDisclosureInteger(snapshot.get(field))) {
                fields.add(field);
            }
        }
        JsonElement featureValue = snapshot.get("features");
        if (featureValue == null || !featureValue.isJsonObject()) {
            fields.add("features");
            return fields;
        }
        var features = featureValue.getAsJsonObject();

        for (String feature : List.of("ai_support", "article_summary")) {
            String path = "features." + feature;
            JsonElement entryList = features.get(feature);
            if (entryList == null || !entryList.isJsonArray() || entryList.getAsJsonArray().isEmpty()) {
                fields.add(path);
                continue;
            }
            var entries = entryList.getAsJsonArray();
            for (int index = 0; index < entries.size(); index++) {
                String entryPath = path + "[" + index + "]";
                JsonElement entryValue = entries.get(index);
                if (!entryValue.isJsonObject()) {
                    fields.add(entryPath);
                    continue;
                }
                var entry = entryValue.getAsJsonObject();
                for (String field : REQUIRED_DISCLOSURE_TEXT) {
                    if (!isDisclosureText(entry.get(field))) {
                        fields.add(entryPath + "." + field);
                    }
                }
                for (String field : OPTIONAL_DISCLOSURE_TEXT) {
                    JsonElement text = entry.get(field);
                    if (text != null && !text.isJsonNull() && !isDisclosureText(text)) {
                        fields.add(entryPath + "." + field);
                    }
                }
            }
        }
        if (fields.isEmpty() && json.contains(PLACEHOLDER)) {
            fields.add(AI_DISCLOSURE_ASSET);
        }
        return fields;
    }

    private static boolean isDisclosureInteger(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            Long.parseLong(value.getAsString());
            return true;
        } catch (NumberFormatException invalidInteger) {
            return false;
        }
    }

    private static boolean isDisclosureText(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return false;
        }
        String text = value.getAsString();
        return !text.isBlank() && !text.contains(PLACEHOLDER);
    }

    private static boolean isHttpsBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535))
                    && !uri.getRawAuthority().endsWith(":");
        } catch (IllegalArgumentException invalidUri) {
            return false;
        }
    }
}
