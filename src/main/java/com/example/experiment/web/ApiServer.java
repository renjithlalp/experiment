package com.example.experiment.web;

import com.example.experiment.Json;
import com.example.experiment.catalogue.ProductCatalogue;
import com.example.experiment.domain.Experiment;
import com.example.experiment.domain.ExperimentConfig;
import com.example.experiment.domain.ExperimentState;
import com.example.experiment.domain.LifecycleAction;
import com.example.experiment.service.ExperimentException;
import com.example.experiment.service.ExperimentService;
import com.example.experiment.service.FieldError;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * JSON REST API on the JDK HTTP server.
 *
 * <pre>
 * GET  /health
 * GET  /api/catalogue
 * POST /api/experiments                              create a Draft (201)
 * GET  /api/experiments[?state=RUNNING]              list
 * GET  /api/experiments/{id}                         current state (ETag = version)
 * PUT  /api/experiments/{id}                         replace the configuration of a Draft (If-Match: version)
 * POST /api/experiments/{id}/actions/{action}        publish | unpublish | pause | resume | end
 * GET  /api/experiments/{id}/versions                full history, oldest first
 * GET  /api/experiments/{id}/versions/{n}            one version
 * GET  /api/experiments/{id}/served?at={instant}     which configuration version was live at that time
 * GET  /api/bucketing/active                         Running experiments for the bucketing engine
 * </pre>
 *
 * Mutating requests need an {@code X-User} header naming who made the change.
 */
public final class ApiServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());
    private static final String JSON = "application/json; charset=utf-8";

    private final ExperimentService service;
    private final ProductCatalogue catalogue;
    private HttpServer server;

    public ApiServer(ExperimentService service, ProductCatalogue catalogue) {
        this.service = service;
        this.catalogue = catalogue;
    }

    /** Starts listening; port 0 picks a free port. */
    public synchronized ApiServer start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (ExperimentException e) {
            sendError(ex, e.status(), e.code(), e.getMessage(), e.errors());
        } catch (JsonProcessingException e) {
            sendError(ex, 400, ExperimentException.VALIDATION_FAILED, describe(e), List.of());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unhandled error for " + ex.getRequestMethod() + " " + ex.getRequestURI(), e);
            sendError(ex, 500, "INTERNAL_ERROR", "Unexpected error", List.of());
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        List<String> p = segments(ex.getRequestURI());

        if (p.equals(List.of("health"))) {
            requireMethod(method, "GET");
            send(ex, 200, Map.of("status", "UP"));
            return;
        }
        if (p.size() < 2 || !p.get(0).equals("api")) {
            throw ExperimentException.notFound("Path " + ex.getRequestURI().getPath());
        }
        String resource = p.get(1);
        if (resource.equals("catalogue") && p.size() == 2) {
            requireMethod(method, "GET");
            send(ex, 200, Map.of("productCodes", catalogue.productCodes(), "fareBrands", catalogue.fareBrandCodes()));
            return;
        }
        if (resource.equals("bucketing") && p.size() == 3 && p.get(2).equals("active")) {
            requireMethod(method, "GET");
            send(ex, 200, service.activeExperiments());
            return;
        }
        if (!resource.equals("experiments")) {
            throw ExperimentException.notFound("Path " + ex.getRequestURI().getPath());
        }
        routeExperiments(ex, method, p.subList(2, p.size()));
    }

    private void routeExperiments(HttpExchange ex, String method, List<String> p) throws IOException {
        if (p.isEmpty()) {
            if (method.equals("POST")) {
                Experiment created = service.create(readConfig(ex), user(ex));
                ex.getResponseHeaders().add("Location", "/api/experiments/" + created.id());
                sendExperiment(ex, 201, created);
            } else {
                requireMethod(method, "GET");
                send(ex, 200, service.list(stateParam(query(ex).get("state"))));
            }
            return;
        }
        String id = p.get(0);
        if (p.size() == 1) {
            if (method.equals("PUT")) {
                sendExperiment(ex, 200, service.update(id, ifMatch(ex), readConfig(ex), user(ex)));
            } else {
                requireMethod(method, "GET");
                sendExperiment(ex, 200, service.get(id));
            }
            return;
        }
        String sub = p.get(1);
        if (sub.equals("actions") && p.size() == 3) {
            requireMethod(method, "POST");
            LifecycleAction action = LifecycleAction.parse(p.get(2));
            if (action == null) {
                throw ExperimentException.notFound("Action '" + p.get(2) + "'");
            }
            ActionRequest body = readOptional(ex, ActionRequest.class);
            String reason = body == null ? null : body.reason();
            sendExperiment(ex, 200, service.apply(id, action, ifMatch(ex), user(ex), reason));
            return;
        }
        if (sub.equals("versions") && p.size() == 2) {
            requireMethod(method, "GET");
            send(ex, 200, service.versions(id));
            return;
        }
        if (sub.equals("versions") && p.size() == 3) {
            requireMethod(method, "GET");
            send(ex, 200, service.version(id, parseLong("version", p.get(2))));
            return;
        }
        if (sub.equals("served") && p.size() == 2) {
            requireMethod(method, "GET");
            String at = query(ex).get("at");
            send(ex, 200, service.servedAt(id, at == null ? Instant.now() : parseInstant(at)));
            return;
        }
        throw ExperimentException.notFound("Path " + ex.getRequestURI().getPath());
    }

    /** Optional body of a lifecycle action. */
    public record ActionRequest(String reason) {
    }

    // ---------------------------------------------------------------- request helpers

    private static ExperimentConfig readConfig(HttpExchange ex) throws IOException {
        ExperimentConfig config = readOptional(ex, ExperimentConfig.class);
        if (config == null) {
            throw ExperimentException.badRequest("body", "a JSON body with the experiment details is required");
        }
        return config;
    }

    private static <T> T readOptional(HttpExchange ex, Class<T> type) throws IOException {
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readAllBytes();
        }
        if (new String(body, StandardCharsets.UTF_8).isBlank()) {
            return null;
        }
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ExperimentException(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json",
                    null);
        }
        return Json.MAPPER.readValue(body, type);
    }

    private static String user(HttpExchange ex) {
        String user = ex.getRequestHeaders().getFirst("X-User");
        return user == null ? null : user.trim();
    }

    private static Long ifMatch(HttpExchange ex) {
        String v = ex.getRequestHeaders().getFirst("If-Match");
        if (v == null || v.isBlank()) {
            return null;
        }
        return parseLong("If-Match", v.trim().replaceFirst("^W/", "").replace("\"", ""));
    }

    private static long parseLong(String field, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw ExperimentException.badRequest(field, "'" + value + "' is not a whole number");
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ExperimentException.badRequest("at", "'" + value + "' is not an ISO-8601 instant such as "
                    + "2027-01-04T10:00:00Z");
        }
    }

    private static ExperimentState stateParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ExperimentState.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ExperimentException.badRequest("state", "'" + value + "' is not a state (allowed: "
                    + Arrays.toString(ExperimentState.values()) + ")");
        }
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new ExperimentException(405, "METHOD_NOT_ALLOWED", actual + " is not allowed here; use " + expected,
                    null);
        }
    }

    private static List<String> segments(URI uri) {
        return Arrays.stream(uri.getPath().split("/")).filter(s -> !s.isEmpty())
                .map(s -> URLDecoder.decode(s, StandardCharsets.UTF_8)).toList();
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    /** Turns Jackson's parse errors into a message that names the field and the allowed values. */
    private static String describe(JsonProcessingException e) {
        String path = e instanceof JsonMappingException m ? m.getPath().stream()
                .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                .collect(Collectors.joining(".")).replace(".[", "[") : "";
        if (e instanceof UnrecognizedPropertyException u) {
            return "Unknown field '" + path + "'";
        }
        if (e instanceof InvalidFormatException f && f.getTargetType() != null && f.getTargetType().isEnum()) {
            return "Invalid value '" + f.getValue() + "' for " + path + " (allowed: "
                    + Arrays.toString(f.getTargetType().getEnumConstants()) + ")";
        }
        if (e instanceof InvalidFormatException f) {
            return "Invalid value '" + f.getValue() + "' for " + path;
        }
        return path.isEmpty() ? "Malformed JSON: " + e.getOriginalMessage()
                : "Invalid value for " + path + ": " + e.getOriginalMessage();
    }

    // ---------------------------------------------------------------- response helpers

    private static void sendExperiment(HttpExchange ex, int status, Experiment e) throws IOException {
        ex.getResponseHeaders().set("ETag", "\"" + e.version() + "\"");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("experiment", e);
        body.put("allowedActions", LifecycleAction.userActionsFrom(e.state()).stream()
                .map(LifecycleAction::label).toList());
        body.put("editable", e.state() == ExperimentState.DRAFT);
        send(ex, status, body);
    }

    private static void sendError(HttpExchange ex, int status, String code, String message, List<FieldError> errors)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("code", code);
        body.put("message", message);
        body.put("errors", errors);
        send(ex, status, body);
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", JSON);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
