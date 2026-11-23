package dev.msa2ssid.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Small HTTP helper shared by every platform front end. */
public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Same client, but a redirect is a result rather than something to follow. */
    private static final HttpClient NO_REDIRECT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String UA = "msa2ssid-accounts/1.0";

    private Http() {
    }

    /** A failed request, with the HTTP status and the raw response body. */
    public static final class ApiException extends Exception {
        public final int status;
        public final String body;
        /** The server's Retry-After wait in seconds, or 0 when it asked for none. */
        public final long retryAfterSeconds;

        public ApiException(String message, int status, String body) {
            this(message, status, body, 0);
        }

        public ApiException(String message, int status, String body, long retryAfterSeconds) {
            super(message);
            this.status = status;
            this.body = body;
            this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
        }
    }

    public static String send(String method, String url, String body, String contentType,
                              Map<String, String> headers) throws ApiException {
        try {
            return sendOrThrow(method, url, body, contentType, headers);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot reach " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while calling " + url, e);
        }
    }

    private static String sendOrThrow(String method, String url, String body, String contentType,
                                      Map<String, String> headers)
            throws ApiException, IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", UA);
        if (headers != null) headers.forEach(b::header);
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", contentType == null ? "application/json" : contentType);
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        HttpResponse<String> r = CLIENT.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() / 100 != 2) {
            throw new ApiException(describe(url, r.statusCode(), r.body()), r.statusCode(), r.body(),
                    retryAfter(r));
        }
        return r.body();
    }

    public static String sendBytes(String method, String url, byte[] body, String contentType,
                                   Map<String, String> headers) throws ApiException {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", UA);
            if (headers != null) headers.forEach(b::header);
            b.header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<String> r = CLIENT.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 != 2) {
                throw new ApiException(describe(url, r.statusCode(), r.body()), r.statusCode(), r.body(),
                        retryAfter(r));
            }
            return r.body();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot reach " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while calling " + url, e);
        }
    }

    /** A ready-to-send multipart/form-data body and its Content-Type header. */
    public record Multipart(byte[] body, String contentType) {
    }

    /** Builds a multipart/form-data body with text fields plus one file part. */
    public static Multipart multipart(Map<String, String> fields, String fileField,
                                      String filename, String fileType, byte[] file) {
        String boundary = "----msa2ssid" + Long.toHexString(System.nanoTime());
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (Map.Entry<String, String> e : fields.entrySet()) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
                    + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + fileType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(file);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return new Multipart(out.toByteArray(), "multipart/form-data; boundary=" + boundary);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A request that stops at the first response, kept for sign-in redirect hops. */
    public record Redirect(int status, String location) {
    }

    /**
     * Sends a GET and reports where the server pointed instead of following it, so a
     * caller can read an authorization code out of the Location header.
     */
    public static Redirect getRedirect(String url, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", UA);
            if (headers != null) headers.forEach(b::header);
            HttpResponse<String> r = NO_REDIRECT.send(b.GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Redirect(r.statusCode(), r.headers().firstValue("Location").orElse(""));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot reach " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while calling " + url, e);
        }
    }

    public static byte[] getBytes(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", UA)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " from " + url);
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching " + url, e);
        }
    }

    /** The Retry-After header in whole seconds; the HTTP-date form is ignored. */
    private static long retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("");
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String describe(String url, int status, String body) {
        String detail = null;
        try {
            Map<String, Object> m = Json.object(body == null ? "" : body);
            for (String key : new String[]{"errorMessage", "error_description", "error", "Message", "message", "path"}) {
                Object v = m.get(key);
                if (v instanceof String s && !s.isBlank()) {
                    detail = s;
                    break;
                }
            }
            Object xerr = m.get("XErr");
            if (xerr != null) detail = (detail == null || detail.isBlank() ? "" : detail + " ") + "XErr=" + xerr;
        } catch (RuntimeException ignored) {
            // not JSON
        }
        if (detail == null) detail = body == null ? "" : body.strip();
        if (detail.length() > 300) detail = detail.substring(0, 300) + "...";
        return shortName(url) + " returned HTTP " + status + (detail.isEmpty() ? "" : ": " + detail);
    }

    private static String shortName(String url) {
        String u = url.replace("https://", "");
        int slash = u.indexOf('/');
        return slash > 0 ? u.substring(0, slash) : u;
    }
}
