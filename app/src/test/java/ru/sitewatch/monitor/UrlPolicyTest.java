package ru.sitewatch.monitor;

import java.net.URL;
import org.junit.Test;
import static org.junit.Assert.*;

public class UrlPolicyTest {
    @Test public void defaultsToHttpsAndPreservesHealthCheckQuery() {
        assertEquals("https://example.com/status?key=abc", UrlPolicy.normalize(
                " EXAMPLE.com/status?key=abc#section "));
    }

    @Test public void supportsInternationalDomainsAndIpv6() {
        assertEquals("https://xn--e1afmkfd.xn--p1ai/", UrlPolicy.normalize("https://пример.рф/"));
        assertEquals("https://[::1]:8443/status", UrlPolicy.normalize("https://[::1]:8443/status"));
    }

    @Test public void rejectsCredentialsAndNonHttpSchemes() {
        for (String url : new String[]{"https://alice:secret@example.com/", "https://alice@example.com/",
                "ftp://example.com/", "file:///etc/passwd", "https://example.com:0/",
                "https://example.com:65536/", "https://bad_host/", "https://example.com/a b"}) {
            assertThrows(url, IllegalArgumentException.class, () -> UrlPolicy.normalize(url));
        }
    }

    @Test public void httpRequiresExplicitPermission() throws Exception {
        URL http = new URL("http://example.com/");
        assertThrows(SecurityException.class, () -> UrlPolicy.requireAllowed(http, false));
        assertEquals(http.toExternalForm(), UrlPolicy.requireAllowed(http, true).toExternalForm());
        assertEquals("https", UrlPolicy.requireAllowed(new URL("https://example.com"), false).getProtocol());
    }

    @Test public void httpsCannotDowngradeEvenWhenHttpIsEnabled() throws Exception {
        URL https = new URL("https://example.com/");
        URL http = new URL("http://example.com/");
        assertThrows(SecurityException.class, () -> UrlPolicy.requireRedirect(https, http, true));
        assertEquals(https.toExternalForm(), UrlPolicy.requireRedirect(http, https, true).toExternalForm());
    }

    @Test public void validatesRedirectCredentialsAndSchemes() throws Exception {
        URL from = new URL("https://example.com/");
        for (String value : new String[]{"https://user:secret@example.com/", "ftp://example.com/"}) {
            URL target = new URL(value);
            SecurityException error = assertThrows(SecurityException.class,
                    () -> UrlPolicy.requireRedirect(from, target, true));
            assertFalse(error.getMessage().contains("secret"));
            assertFalse(error.getMessage().contains(value));
        }
    }

    @Test public void notificationsNeverContainPathQueryOrFragment() {
        String host = UrlPolicy.notificationHost("https://example.com/private-secret?token=top-secret#hidden");
        assertEquals("example.com", host);
        assertEquals("Сайт из вашего списка", UrlPolicy.notificationHost("not a url?token=secret"));
        assertEquals("Сайт из вашего списка", UrlPolicy.notificationHost("https://user:secret@example.com"));
    }
}
