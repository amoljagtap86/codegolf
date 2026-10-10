package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    void rootServesHomePage() throws Exception {
        // Twice: Grizzly's built-in static handler dropped Content-Type on repeat requests.
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/")).GET().build());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
                    response.headers().map().toString());
            assertTrue(response.body().contains("Hello, world"), response.body());
        }
    }

    @Test
    void missingStaticFileReturns404() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/nope.html")).GET().build());
        assertEquals(404, response.statusCode());
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
