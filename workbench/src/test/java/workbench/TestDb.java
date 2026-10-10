package workbench;

import java.sql.Connection;
import java.sql.Statement;

/** Test database from TEST_DATABASE_URL. Tests wipe its tables, so never point it at real data. */
final class TestDb {

    private TestDb() {
    }

    static Db fresh() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("TEST_DATABASE_URL is not set. See README.md.");
        }
        Db db = new Db(url);
        db.applySchema();
        try (Connection c = db.connect(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE step_attempts, executions RESTART IDENTITY");
        }
        return db;
    }
}
