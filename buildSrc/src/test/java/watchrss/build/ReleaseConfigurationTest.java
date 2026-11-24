package watchrss.build;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ReleaseConfigurationTest {
    @Test
    public void resolvesFirstNonBlankValueInOrder() {
        assertEquals("gradle", ReleaseConfiguration.firstNonBlank(
                () -> " \t\n", () -> " gradle ", () -> {
                    fail("Later sources must not be read");
                    return null;
                }));
        assertEquals("environment", ReleaseConfiguration.firstNonBlank(
                () -> null, () -> "", () -> "environment"));
        assertEquals("", ReleaseConfiguration.firstNonBlank(() -> null, () -> " "));
    }

    @Test
    public void acceptsHttpsWithHostAndOptionalPathOrPort() {
        for (String url : List.of("https://example.test", "HTTPS://example.test:443/base",
                "https://[::1]:8443")) {
            assertEquals(List.of(), ReleaseConfiguration.invalidFields(url, "anon", "public"));
        }
    }

    @Test
    public void reportsOnlyNamesOfInvalidUrlFields() {
        for (String url : List.of("", " ", "https:/example.test", "https://",
                "http://example.test", "https://bad host", "https://example.test:65536",
                "https://example.test:", "https://user@example.test",
                "https://example.test?x=y", "https://example.test#fragment")) {
            assertEquals(List.of(ReleaseConfiguration.BACKEND_URL),
                    ReleaseConfiguration.invalidFields(url, "anon", "public"));
        }
    }

    @Test
    public void rejectsBlankAuthenticationSettings() {
        assertEquals(List.of(ReleaseConfiguration.ANON_KEY, ReleaseConfiguration.APP_ACCESS_KEY),
                ReleaseConfiguration.invalidFields("https://example.test", " ", "\t"));
    }
}
