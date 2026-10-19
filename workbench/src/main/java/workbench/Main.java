package workbench;

import java.io.InputStream;
import java.net.URI;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.MimeType;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

public class Main {

    public static void main(String[] args) throws Exception {
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            System.err.println("DATABASE_URL is not set. See README.md.");
            System.exit(1);
        }
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        HttpServer server = start(new Db(databaseUrl), port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdownNow));
        System.out.println("Workbench running at http://localhost:" + server.getListener("grizzly").getPort() + "/");
        Thread.currentThread().join();
    }

    /**
     * Applies the schema and starts the HTTP server: JAX-RS resources under /api, static files
     * from src/main/resources/static under /. Port 0 picks a free port (used by tests).
     */
    static HttpServer start(Db db, int port) throws Exception {
        ProcessDefinition.IC_MONTHLY_RUN.size(); // loads, and so validates, the process DAG before serving anything
        db.applySchema();
        ResourceConfig config = new ResourceConfig()
                .register(JsonConfig.class)
                .register(new ExecutionResource(db, new Runner(db)));
        HttpServer server = GrizzlyHttpServerFactory.createHttpServer(
                URI.create("http://localhost:" + port + "/api"), config, false);
        server.getServerConfiguration().addHttpHandler(new StaticFiles(), "/");
        server.start();
        return server;
    }

    /** Serves files from src/main/resources/static; "/" maps to index.html. */
    static class StaticFiles extends HttpHandler {

        @Override
        public void service(Request request, Response response) throws Exception {
            String path = request.getRequestURI();
            if (path.endsWith("/")) {
                path += "index.html";
            }
            if (path.contains("..")) { // keep requests inside /static on the classpath
                response.sendError(404);
                return;
            }
            try (InputStream in = Main.class.getResourceAsStream("/static" + path)) {
                if (in == null) {
                    response.sendError(404);
                    return;
                }
                byte[] body = in.readAllBytes();
                String type = MimeType.getByFilename(path);
                response.setContentType(type != null ? type : "application/octet-stream");
                response.setContentLength(body.length);
                response.getOutputStream().write(body);
            }
        }
    }
}
