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

    private final HttpClient client = HttpClient.newHttpClient();
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

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
