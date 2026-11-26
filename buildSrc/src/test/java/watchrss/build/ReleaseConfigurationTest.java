package watchrss.build;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ReleaseConfigurationTest {
    private static final String VALID_DISCLOSURE = """
            {
              "revision": 3,
              "updatedAt": 1795000000000,
              "features": {
                "ai_support": [{"displayText": "Support", "modelName": "Model", "launchNumber": null}],
                "article_summary": [{"displayText": "Summary", "modelName": "Model"}]
              }
            }
            """;

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

    @Test
    public void acceptsValidAiDisclosureWithNullableFields() {
        assertEquals(List.of(), ReleaseConfiguration.invalidAiDisclosureFields(VALID_DISCLOSURE));
    }

    @Test
    public void rejectsMissingEmptyOrMalformedAiDisclosure() {
        for (String snapshot : List.of("", " ", "{", "[]", VALID_DISCLOSURE + " trailing")) {
            assertEquals(List.of(ReleaseConfiguration.AI_DISCLOSURE_ASSET),
                    ReleaseConfiguration.invalidAiDisclosureFields(snapshot));
        }
        assertEquals(List.of(ReleaseConfiguration.AI_DISCLOSURE_ASSET),
                ReleaseConfiguration.invalidAiDisclosureFields(null));
    }

    @Test
    public void reportsOnlyInvalidAiDisclosureFieldPaths() {
        String invalid = VALID_DISCLOSURE
                .replace("Support", " ")
                .replace("null", "\"prefix __FILL_BEFORE_RELEASE__ suffix\"")
                .replace("[{\"displayText\": \"Summary\", \"modelName\": \"Model\"}]", "[]")
                .replace("\"revision\": 3", "\"revision\": true");
        assertEquals(List.of("revision", "features.ai_support[0].displayText",
                        "features.ai_support[0].launchNumber", "features.article_summary"),
                ReleaseConfiguration.invalidAiDisclosureFields(invalid));
    }
}
