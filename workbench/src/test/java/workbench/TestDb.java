package workbench;

import java.sql.Connection;
import java.sql.Statement;

/** Test database from TEST_DATABASE_URL. Tests wipe its tables, so never point it at real data. */
final class TestDb {

    private TestDb() {
    }

    /** The test database with the schema applied and all tables emptied. */
    static Db fresh() throws Exception {
        Db db = existing();
        db.applySchema();
        try (Connection c = db.connect(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE step_attempts, executions RESTART IDENTITY");
        }
        return db;
    }

    /** The test database as it is, e.g. to add rows after fresh() ran in setUp. */
    static Db existing() {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("TEST_DATABASE_URL is not set. See README.md.");
        }
        return new Db(url);
    }
}
