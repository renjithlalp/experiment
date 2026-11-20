package com.example.experiment.web;

import static com.example.experiment.Fixtures.NOW;
import static com.example.experiment.Fixtures.START;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.experiment.Fixtures.MutableClock;
import com.example.experiment.Json;
import com.example.experiment.catalogue.InMemoryProductCatalogue;
import com.example.experiment.service.ExperimentService;
import com.example.experiment.store.InMemoryExperimentRepository;
import com.example.experiment.validation.ExperimentValidator;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiServerTest {

    private static final String CREATE = """
            {"name":"Seat upgrade discount","description":"Test whether 10% off lifts upgrades",
             "startAt":"2027-01-04T00:00:00Z","endAt":"2027-02-01T00:00:00Z",
             "treatmentType":"PRICING","priority":10}""";

    private static final String COMPLETE = """
            {"name":"Seat upgrade discount","description":"Test whether 10% off lifts upgrades",
             "startAt":"2027-01-04T00:00:00Z","endAt":"2027-02-01T00:00:00Z",
             "treatmentType":"PRICING","priority":10,
             "split":{"testPercent":90,"controlPercent":10},
             "bucketing":{"primary":"USER_ID","fallbackOrder":["DEVICE_COOKIE_ID","SESSION_ID"]},
             "pricing":{"adjustment":"DISCOUNT","valueType":"PERCENT","value":10,"currency":"EUR",
                        "floor":15,"ceiling":200,"productCodes":["SEAT_UPGRADE"],"fareBrands":["STANDARD"]}}""";

    private final HttpClient http = HttpClient.newHttpClient();
    private MutableClock clock;
    private ExperimentService service;
    private ApiServer server;

    @BeforeEach
    void start() throws Exception {
        clock = new MutableClock(NOW);
        var catalogue = InMemoryProductCatalogue.sample();
        service = new ExperimentService(new InMemoryExperimentRepository(), new ExperimentValidator(catalogue), clock);
        server = new ApiServer(service, catalogue).start(0);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private record Res(int status, JsonNode body, HttpResponse<String> raw) {
    }

    private Res call(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), Json.MAPPER.readTree(r.body()), r);
    }

    @Test
    void fullFlowOverHttp() throws Exception {
        Res created = call("POST", "/api/experiments", CREATE, "X-User", "maria");
        assertEquals(201, created.status());
        String id = created.body().at("/experiment/id").asText();
        assertEquals("/api/experiments/" + id, created.raw().headers().firstValue("Location").orElseThrow());
        assertEquals("\"1\"", created.raw().headers().firstValue("ETag").orElseThrow());
        assertEquals("DRAFT", created.body().at("/experiment/state").asText());
        assertTrue(created.body().at("/editable").asBoolean());

        Res incomplete = call("POST", "/api/experiments/" + id + "/actions/publish", null, "X-User", "maria");
        assertEquals(400, incomplete.status());
        assertEquals(3, incomplete.body().at("/errors").size());

        Res updated = call("PUT", "/api/experiments/" + id, COMPLETE, "X-User", "maria", "If-Match", "\"1\"");
        assertEquals(200, updated.status(), updated.body().toString());
        assertEquals(2, updated.body().at("/experiment/version").asInt());

        Res published = call("POST", "/api/experiments/" + id + "/actions/publish", "{\"reason\":\"approved\"}",
                "X-User", "maria");
        assertEquals("SCHEDULED", published.body().at("/experiment/state").asText());

        clock.set(START);
        service.tick();

        Res feed = call("GET", "/api/bucketing/active", null);
        assertEquals(1, feed.body().size());
        assertEquals(id, feed.body().at("/0/experimentId").asText());
        assertEquals(2, feed.body().at("/0/configVersion").asInt());
        assertEquals("USER_ID", feed.body().at("/0/primaryIdentifier").asText());

        Res locked = call("PUT", "/api/experiments/" + id, COMPLETE.replace("\"testPercent\":90,\"controlPercent\":10",
                "\"testPercent\":50,\"controlPercent\":50"), "X-User", "maria");
        assertEquals(409, locked.status());
        assertEquals("EXPERIMENT_LOCKED", locked.body().at("/code").asText());

        clock.advance(java.time.Duration.ofDays(1));
        Res paused = call("POST", "/api/experiments/" + id + "/actions/pause", null, "X-User", "maria");
        assertEquals("PAUSED", paused.body().at("/experiment/state").asText());
        assertEquals("[\"resume\",\"end\"]", paused.body().at("/allowedActions").toString());
        assertEquals(0, call("GET", "/api/bucketing/active", null).body().size());

        Res served = call("GET", "/api/experiments/" + id + "/served?at=2027-01-04T00:00:01Z", null);
        assertEquals("RUNNING", served.body().at("/state").asText());
        assertEquals(2, served.body().at("/configVersion").asInt());

        Res versions = call("GET", "/api/experiments/" + id + "/versions", null);
        assertEquals(5, versions.body().size());
        assertEquals("approved", versions.body().at("/2/reason").asText());
        assertEquals("system", versions.body().at("/3/changedBy").asText());

        Res list = call("GET", "/api/experiments?state=paused", null);
        assertEquals(1, list.body().size());
    }

    @Test
    void unknownFieldsAndEnumValuesAreExplained() throws Exception {
        Res typo = call("POST", "/api/experiments", CREATE.replace("\"priority\"", "\"prio\""), "X-User", "maria");
        assertEquals(400, typo.status());
        assertEquals("Unknown field 'prio'", typo.body().at("/message").asText());

        Res badEnum = call("POST", "/api/experiments", CREATE.replace("PRICING", "RECOMMENDATION"),
                "X-User", "maria");
        assertEquals(400, badEnum.status());
        assertTrue(badEnum.body().at("/message").asText().contains("allowed: [PRICING]"));

        Res fraction = call("POST", "/api/experiments", COMPLETE.replace("\"testPercent\":90", "\"testPercent\":89.5"),
                "X-User", "maria");
        assertEquals(400, fraction.status());
    }

    @Test
    void validationErrorsListEveryField() throws Exception {
        Res res = call("POST", "/api/experiments", COMPLETE.replace("\"controlPercent\":10", "\"controlPercent\":0")
                .replace("\"floor\":15", "\"floor\":500"), "X-User", "maria");
        assertEquals(400, res.status());
        assertEquals("VALIDATION_FAILED", res.body().at("/code").asText());
        String fields = res.body().at("/errors").findValuesAsText("field").toString();
        assertTrue(fields.contains("split.controlPercent"), fields);
        assertTrue(fields.contains("split"), fields);
        assertTrue(fields.contains("pricing.floor"), fields);
    }

    @Test
    void userHeaderIsRequiredForChanges() throws Exception {
        Res res = call("POST", "/api/experiments", CREATE);
        assertEquals(400, res.status());
        assertEquals("X-User", res.body().at("/errors/0/field").asText());
    }

    @Test
    void notFoundAndMethodErrors() throws Exception {
        assertEquals(404, call("GET", "/api/experiments/EXP-999999", null).status());
        assertEquals(404, call("GET", "/nope", null).status());
        assertEquals(405, call("DELETE", "/api/bucketing/active", null).status());
        Res created = call("POST", "/api/experiments", CREATE, "X-User", "maria");
        String id = created.body().at("/experiment/id").asText();
        assertEquals(404, call("POST", "/api/experiments/" + id + "/actions/explode", null, "X-User", "m").status());
        assertEquals(409, call("POST", "/api/experiments/" + id + "/actions/start", null, "X-User", "m").status());
        assertEquals(200, call("GET", "/health", null).status());
        assertEquals(200, call("GET", "/api/catalogue", null).status());
    }
}
