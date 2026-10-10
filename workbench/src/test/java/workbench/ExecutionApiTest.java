package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.glassfish.grizzly.http.server.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Starts the real HTTP server on a free port and calls it over HTTP. */
class ExecutionApiTest {

    // HTTP/1.1: the default (HTTP/2) sends "Upgrade: h2c" on plain http, and Grizzly then withholds
    // the request body, so any POST with a body hangs for 30 s and fails. See README, Known limitations.
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = Main.start(TestDb.fresh(), 0);
        baseUrl = "http://localhost:" + server.getListener("grizzly").getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) { // null when setUp failed, e.g. database unreachable
            server.shutdownNow();
        }
    }

    @Test
    void postCreatesAndRunsExecutionThenGetReturnsIt() throws Exception {
        HttpResponse<String> created = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/executions"))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertEquals(201, created.statusCode());
        String location = created.headers().firstValue("Location").orElseThrow();
        assertTrue(location.endsWith("/api/executions/1"), location);
        assertTrue(created.body().contains("\"status\":\"SUCCEEDED\""), created.body());

        HttpResponse<String> fetched = send(HttpRequest.newBuilder(URI.create(location)).GET().build());
        assertEquals(200, fetched.statusCode());
        assertTrue(fetched.body().contains("\"stepName\":\"SEND_CONFIRMATION\""), fetched.body());
    }

    @Test
    void postWithFailurePlanReturns201WithFailedExecution() throws Exception {
        HttpResponse<String> created = postJson("{\"failStep\": 2}");
        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("\"status\":\"FAILED\""), created.body());
        assertTrue(created.body().contains("\"failurePlan\":{\"failStep\":2,\"failTimes\":1}"), created.body());
        assertTrue(created.body().contains("\"errorMessage\":\"RESERVE_INVENTORY failed"), created.body());
    }

    @Test
    void invalidFailurePlanReturns400AndCreatesNothing() throws Exception {
        assertEquals(400, postJson("{\"failStep\": 7}").statusCode());
        assertEquals(400, postJson("{\"failStep\": 1, \"failTimes\": 0}").statusCode());
        assertEquals(400, postJson("{not json").statusCode());

        // Ids restart at 1 per test, so nothing created means execution 1 does not exist.
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/executions/1"))
                .GET().build());
        assertEquals(404, response.statusCode());
    }

    @Test
    void retryOfFailedExecutionReturns200AndRetryOfSucceededReturns409() throws Exception {
        assertEquals(201, postJson("{\"failStep\": 4}").statusCode());

        HttpResponse<String> retried = post("/api/executions/1/retry");
        assertEquals(200, retried.statusCode(), retried.body());
        assertTrue(retried.body().contains("\"status\":\"SUCCEEDED\""), retried.body());

        HttpResponse<String> again = post("/api/executions/1/retry");
        assertEquals(409, again.statusCode());
        assertTrue(again.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(again.body().contains("Execution 1 is SUCCEEDED; only FAILED executions can be retried"),
                again.body());
    }

    @Test
    void retryOfUnknownExecutionReturns404() throws Exception {
        assertEquals(404, post("/api/executions/999/retry").statusCode());
    }

    @Test
    void getUnknownExecutionReturns404() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/executions/999"))
                .GET().build());
        assertEquals(404, response.statusCode());
    }

    @Test
    void staticFilesAreServedWithCorrectContentType() throws Exception {
        // Browsers refuse scripts and stylesheets served with the wrong type. Each is fetched twice:
        // Grizzly's built-in static handler dropped Content-Type on repeat requests.
        Map<String, String> expected = Map.of(
                "/", "text/html",
                "/execution.html", "text/html",
                "/app.js", "text/javascript",
                "/style.css", "text/css");
        for (Map.Entry<String, String> file : expected.entrySet()) {
            for (int i = 0; i < 2; i++) {
                HttpResponse<String> response = get(file.getKey());
                assertEquals(200, response.statusCode(), file.getKey());
                assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith(file.getValue()),
                        file.getKey() + " " + response.headers().map());
            }
        }
        assertTrue(get("/").body().contains("Execution history"));
    }

    @Test
    void listReturnsNewestFirstWithAttemptAndRetryCounts() throws Exception {
        assertEquals(201, post("/api/executions").statusCode());               // 1: 5 attempts, 0 retries
        assertEquals(201, postJson("{\"failStep\": 2, \"failTimes\": 2}").statusCode()); // 2: FAILED
        assertEquals(200, post("/api/executions/2/retry").statusCode());       // 2: FAILED again
        assertEquals(200, post("/api/executions/2/retry").statusCode());       // 2: SUCCEEDED

        HttpResponse<String> response = get("/api/executions");
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.indexOf("\"id\":2") < body.indexOf("\"id\":1"), "newest first: " + body);
        // Execution 2: step 1 once, step 2 three times, steps 3-5 once = 7 attempts, 2 retries.
        assertTrue(body.contains("\"id\":2,\"status\":\"SUCCEEDED\",\"failurePlan\":{\"failStep\":2,\"failTimes\":2}"), body);
        assertTrue(body.contains("\"attemptCount\":7,\"retryCount\":2"), body);
        assertTrue(body.contains("\"attemptCount\":5,\"retryCount\":0"), body);
        assertTrue(body.contains("\"failurePlan\":null"), body);
    }

    @Test
    void listHonoursAndValidatesLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            post("/api/executions");
        }
        String body = get("/api/executions?limit=2").body();
        assertTrue(body.contains("\"id\":3") && body.contains("\"id\":2") && !body.contains("\"id\":1"), body);

        assertEquals(400, get("/api/executions?limit=0").statusCode());
        assertEquals(400, get("/api/executions?limit=201").statusCode());
    }

    @Test
    void stepsReturnsProcessDefinition() throws Exception {
        assertEquals("[\"VALIDATE_ORDER\",\"RESERVE_INVENTORY\",\"CHARGE_PAYMENT\",\"CREATE_SHIPMENT\",\"SEND_CONFIRMATION\"]",
                get("/api/executions/steps").body());
    }

    @Test
    void missingStaticFileReturns404() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/nope.html")).GET().build());
        assertEquals(404, response.statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private HttpResponse<String> postJson(String json) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/executions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
