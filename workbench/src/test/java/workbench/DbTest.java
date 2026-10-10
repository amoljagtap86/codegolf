package workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Connection-string parsing only; no database needed. */
class DbTest {

    @Test
    void jdbcUrlIsUsedAsIs() {
        Db db = new Db("jdbc:postgresql://localhost:5432/app?user=u&password=p");
        assertEquals("jdbc:postgresql://localhost:5432/app?user=u&password=p", db.jdbcUrl);
        assertEquals(0, db.props.size());
    }

    @Test
    void postgresUrlIsConvertedAndCredentialsDecoded() {
        Db db = new Db("postgresql://alice:p%40ss@db.example.com:6543/app?sslmode=require");
        assertEquals("jdbc:postgresql://db.example.com:6543/app?sslmode=require", db.jdbcUrl);
        assertEquals("alice", db.props.getProperty("user"));
        assertEquals("p@ss", db.props.getProperty("password"));
    }
}
