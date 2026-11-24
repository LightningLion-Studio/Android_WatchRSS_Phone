package watchrss.build;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class ReleaseConfiguration {
    public static final String BACKEND_URL = "WATCHRSS_BACKEND_BASE_URL";
    public static final String ANON_KEY = "WATCHRSS_SUPABASE_ANON_KEY";
    public static final String APP_ACCESS_KEY = "WATCHRSS_APP_ACCESS_PUBLIC_KEY";

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
