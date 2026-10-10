package workbench;

import java.net.URI;
import org.glassfish.grizzly.http.server.HttpServer;
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

    /** Applies the schema and starts the HTTP server. Port 0 picks a free port (used by tests). */
    static HttpServer start(Db db, int port) throws Exception {
        db.applySchema();
        ResourceConfig config = new ResourceConfig()
                .register(JsonConfig.class)
                .register(new ExecutionResource(db, new Runner(db)));
        return GrizzlyHttpServerFactory.createHttpServer(URI.create("http://localhost:" + port + "/"), config);
    }
}
