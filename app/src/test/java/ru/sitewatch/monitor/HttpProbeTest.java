package ru.sitewatch.monitor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpProbeTest {
    private ServerSocket server;
    private Thread serverThread;
    private String base;
    private AtomicInteger requests;
    private AtomicReference<Throwable> serverError;
    private AtomicBoolean permission;

    @Before public void start() throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        base = "http://127.0.0.1:" + server.getLocalPort();
        requests = new AtomicInteger();
        serverError = new AtomicReference<>();
        permission = new AtomicBoolean(true);
        serverThread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    serve(socket);
                } catch (Throwable error) {
                    if (!server.isClosed()) serverError.set(error);
                    break;
                }
            }
        }, "local-http-test");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void serve(Socket socket) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            String firstLine = reader.readLine();
            if (firstLine == null) return;
            String[] requestLine = firstLine.split(" ");
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) { }
            requests.incrementAndGet();
            URI uri = URI.create(requestLine[1]);
            String path = uri.getPath();
            OutputStream response = socket.getOutputStream();
            switch (path) {
                case "/redirect": reply(response, 302, "/healthy"); break;
                case "/revoke":
                    permission.set(false);
                    reply(response, 302, "/healthy"); break;
                case "/loop": reply(response, 302, "/loop"); break;
                case "/credentials": reply(response, 302,
                        base.replace("http://", "http://user:secret@") + "/healthy"); break;
                case "/scheme": reply(response, 302, "ftp://127.0.0.1/private"); break;
                case "/error": reply(response, 502, null); break;
                case "/no-location": reply(response, 302, null); break;
                case "/health-query":
                    int code = "GET".equals(requestLine[0])
                            && "token=abc".equals(uri.getRawQuery()) ? 204 : 400;
                    reply(response, code, null); break;
                default: reply(response, 204, null);
            }
    }

    @After public void stop() throws Exception {
        server.close();
        serverThread.join(3000);
        assertFalse("HTTP test server did not stop", serverThread.isAlive());
        assertNull("HTTP test server failed", serverError.get());
    }

    private static void reply(OutputStream response, int status, String location) throws IOException {
        String headers = "HTTP/1.1 " + status + " Test\r\nConnection: close\r\nContent-Length: 0\r\n";
        if (location != null) headers += "Location: " + location + "\r\n";
        response.write((headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
        response.flush();
    }

    @Test public void blockedHttpMakesNoNetworkRequest() {
        assertThrows(SecurityException.class, () -> HttpProbe.responseCode(base + "/healthy", false));
        assertEquals(0, requests.get());
    }

    @Test public void followsRelativeRedirectWithConsent() throws IOException {
        assertEquals(204, HttpProbe.responseCode(base + "/redirect", true));
        assertEquals(2, requests.get());
    }

    @Test public void revocationDuringRedirectPreventsTheNextRequest() {
        assertThrows(SecurityException.class,
                () -> HttpProbe.responseCode(base + "/revoke", permission::get));
        assertEquals(1, requests.get());
    }

    @Test public void redirectCannotInjectCredentials() {
        assertThrows(SecurityException.class, () -> HttpProbe.responseCode(base + "/credentials", true));
        assertEquals(1, requests.get());
    }

    @Test public void redirectCannotSwitchToOtherProtocols() {
        assertThrows(SecurityException.class, () -> HttpProbe.responseCode(base + "/scheme", true));
        assertEquals(1, requests.get());
    }

    @Test public void redirectLoopsAreBounded() {
        assertThrows(SecurityException.class, () -> HttpProbe.responseCode(base + "/loop", true));
        assertEquals(HttpProbe.MAX_REDIRECTS + 1, requests.get());
    }

    @Test public void retainsRealErrorCode() throws IOException {
        assertEquals(502, HttpProbe.responseCode(base + "/error", true));
    }

    @Test public void redirectWithoutLocationStillReportsActualStatus() throws IOException {
        assertEquals(302, HttpProbe.responseCode(base + "/no-location", true));
        assertEquals(1, requests.get());
    }

    @Test public void preservesGetAndQueryForExistingChecks() throws IOException {
        assertEquals(204, HttpProbe.responseCode(base + "/health-query?token=abc", true));
    }
}
