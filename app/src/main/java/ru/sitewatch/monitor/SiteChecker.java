package ru.sitewatch.monitor;

import android.content.Context;
import java.util.concurrent.TimeUnit;

public final class SiteChecker {
    private SiteChecker() {}

    public static void checkOne(Context ctx, SiteStore.Site site) {
        // HTTP codes exist only when a server actually returns an HTTP response.
        long started = System.nanoTime();
        String state;
        String details;
        String errorType = "";
        int httpCode = -1;
        try {
            int code = HttpProbe.responseCode(site.url, () -> SiteStore.httpEnabled(ctx));
            httpCode = code;
            state = (code >= 200 && code < 400) ? "UP" : "DOWN";
            if (code > 0) {
                details = "HTTP " + code + describeHttpCode(code);
            } else {
                details = "Сервер не вернул корректный HTTP-ответ";
                errorType = "HTTP";
            }
        } catch (SecurityException e) {
            state = "BLOCKED";
            errorType = "POLICY";
            details = e.getMessage();
        } catch (java.net.SocketTimeoutException e) {
            state = "DOWN";
            errorType = "TIMEOUT";
            details = "Тайм-аут: HTTP-ответ не получен (подключение до 12 с, ожидание ответа до 20 с)";
        } catch (javax.net.ssl.SSLException e) {
            state = "DOWN";
            errorType = "SSL";
            details = "Ошибка защищённого соединения HTTPS/SSL";
        } catch (java.net.UnknownHostException e) {
            state = "DOWN";
            errorType = "DNS";
            details = "DNS: не удалось найти IP-адрес сайта";
        } catch (java.net.ConnectException e) {
            state = "DOWN";
            errorType = "CONNECT";
            details = "Не удалось установить соединение с сервером";
        } catch (java.net.NoRouteToHostException e) {
            state = "DOWN";
            errorType = "NETWORK";
            details = "Нет сетевого маршрута до сервера";
        } catch (java.net.SocketException e) {
            state = "DOWN";
            errorType = "CONNECT";
            details = "Соединение с сервером оборвалось";
        } catch (java.net.MalformedURLException e) {
            state = "DOWN";
            errorType = "URL";
            details = "Некорректный URL";
        } catch (Exception e) {
            state = "DOWN";
            errorType = "NETWORK";
            details = "Сетевая ошибка: " + e.getClass().getSimpleName();
        }
        long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        boolean recovered = SiteStore.update(ctx, site.id, state, details,
                httpCode, errorType, System.currentTimeMillis(), latency);
        if (recovered && SiteStore.notificationsEnabled(ctx)) {
            RecoveryNotifier.show(ctx, site.url);
        }
    }

    private static String describeHttpCode(int code) {
        switch (code) {
            case 400: return " — неверный запрос";
            case 401: return " — требуется авторизация";
            case 403: return " — доступ запрещён";
            case 404: return " — страница не найдена";
            case 408: return " — сервер не дождался запроса";
            case 429: return " — слишком много запросов";
            case 500: return " — внутренняя ошибка сервера";
            case 502: return " — ошибка шлюза";
            case 503: return " — сервис временно недоступен";
            case 504: return " — шлюз не дождался ответа";
            case 505: return " — версия HTTP не поддерживается";
            default: return "";
        }
    }
}
