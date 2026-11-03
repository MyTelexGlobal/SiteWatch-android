package ru.sitewatch.monitor;

import java.net.IDN;
import java.net.URI;
import java.net.URL;
import java.util.Locale;

/** Shared validation for user input, stored addresses and every redirect. */
public final class UrlPolicy {
    public static final String HTTP_DISABLED_MESSAGE = "HTTP отключен. Для этого адреса нужен HTTPS " +
            "или разрешение HTTP в настройках";
    private UrlPolicy() {}

    public static String normalize(String input) {
        try {
            String value = input.trim();
            if (value.isEmpty()) throw new IllegalArgumentException("Введите URL сайта");
            if (!value.contains("://")) value = "https://" + value;
            URI uri = new URI(value);
            URL url = uri.toURL();
            String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
            if (!protocol.equals("https") && !protocol.equals("http"))
                throw new IllegalArgumentException("Допускаются только HTTP и HTTPS");
            if (uri.getRawUserInfo() != null || url.getUserInfo() != null)
                throw new IllegalArgumentException("URL с логином и паролем не поддерживается");
            String host = url.getHost();
            if (host == null || host.trim().isEmpty())
                throw new IllegalArgumentException("Неверный адрес сайта");
            if (!host.contains(":")) host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            if (url.getPort() == 0 || url.getPort() > 65535 || url.getPort() < -1)
                throw new IllegalArgumentException("Недопустимый порт");
            return new URL(protocol, host.toLowerCase(Locale.ROOT), url.getPort(),
                    url.getFile()).toExternalForm();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Проверьте адрес (например, https://site.ru)");
        }
    }

    public static URL requireAllowed(URL url, boolean httpEnabled) {
        URL checked;
        try {
            checked = new URL(normalize(url.toExternalForm()));
        } catch (Exception e) {
            // Do not include the URL or exception text: it may contain a secret.
            throw new SecurityException("Недопустимый адрес запроса");
        }
        if ("http".equals(checked.getProtocol()) && !httpEnabled)
            throw new SecurityException(HTTP_DISABLED_MESSAGE);
        return checked;
    }

    public static boolean isHttp(String address) {
        return address != null && address.regionMatches(true, 0, "http://", 0, 7);
    }

    public static URL requireRedirect(URL from, URL to, boolean httpEnabled) {
        URL checked = requireAllowed(to, httpEnabled);
        if ("https".equalsIgnoreCase(from.getProtocol())
                && "http".equals(checked.getProtocol()))
            throw new SecurityException("Переход с HTTPS на незащищенный HTTP заблокирован");
        return checked;
    }

    public static String notificationHost(String value) {
        try {
            return new URL(normalize(value)).getHost();
        } catch (Exception e) {
            return "Сайт из вашего списка";
        }
    }
}
