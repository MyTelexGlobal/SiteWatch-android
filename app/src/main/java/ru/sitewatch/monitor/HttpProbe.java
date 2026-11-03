package ru.sitewatch.monitor;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Fetch response headers without executing or parsing page content. */
public final class HttpProbe {
    public static final int MAX_REDIRECTS = 5;

    public interface HttpPermission {
        boolean allowed();
    }

    private HttpProbe() {}

    public static int responseCode(String address, boolean httpEnabled) throws IOException {
        return responseCode(address, () -> httpEnabled);
    }

    public static int responseCode(String address, HttpPermission permission) throws IOException {
        URL current = new URL(address);
        for (int redirects = 0; ; redirects++) {
            current = UrlPolicy.requireAllowed(current, permission.allowed());
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            try {
                // Automatic redirects would bypass the per-hop policy check.
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                connection.setRequestProperty("Accept", "text/html,*/*;q=0.8");
                connection.setUseCaches(false);
                int code = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                boolean redirect = code == 301 || code == 302 || code == 303
                        || code == 307 || code == 308;
                if (!redirect || location == null || location.trim().isEmpty()) return code;
                if (redirects >= MAX_REDIRECTS)
                    throw new SecurityException("Превышен предел перенаправлений сайта");
                current = UrlPolicy.requireRedirect(current, new URL(current, location), permission.allowed());
            } finally {
                connection.disconnect();
            }
        }
    }
}
