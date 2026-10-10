package workbench;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * All SQL lives here. One connection per call, auto-commit on: every state
 * transition is durable as soon as the method returns.
 */
public class Db {

    final String jdbcUrl;
    final Properties props = new Properties();

    /** Accepts either a JDBC URL or a libpq-style postgres:// / postgresql:// URL. */
    public Db(String connectionString) {
        if (connectionString.startsWith("jdbc:")) {
            jdbcUrl = connectionString;
            return;
        }
        URI uri = URI.create(connectionString);
        String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        jdbcUrl = "jdbc:postgresql://" + uri.getHost() + port + uri.getRawPath() + query;
        if (uri.getRawUserInfo() != null) {
            String[] userInfo = uri.getRawUserInfo().split(":", 2);
            props.setProperty("user", decode(userInfo[0]));
            if (userInfo.length == 2) {
                props.setProperty("password", decode(userInfo[1]));
            }
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, props);
    }

    public void applySchema() throws SQLException, IOException {
        String sql;
        try (InputStream in = Db.class.getResourceAsStream("/schema.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    public long insertRunningExecution() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO executions (status, started_at) VALUES ('RUNNING', now()) RETURNING id")) {
            return singleLong(ps);
        }
    }

    public void finishExecution(long executionId, String status) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE executions SET status = ?, finished_at = now() WHERE id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, executionId);
            ps.executeUpdate();
        }
    }

    public long insertRunningAttempt(long executionId, int stepIndex, String stepName, int attemptNo)
            throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO step_attempts (execution_id, step_index, step_name, attempt_no, status)
                     VALUES (?, ?, ?, ?, 'RUNNING') RETURNING id""")) {
            ps.setLong(1, executionId);
            ps.setInt(2, stepIndex);
            ps.setString(3, stepName);
            ps.setInt(4, attemptNo);
            return singleLong(ps);
        }
    }

    public void finishAttempt(long attemptId, String status) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE step_attempts SET status = ?, finished_at = now() WHERE id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, attemptId);
            ps.executeUpdate();
        }
    }

    public Optional<Execution> findExecution(long executionId) throws SQLException {
        try (Connection c = connect()) {
            List<StepAttempt> attempts = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, step_index, step_name, attempt_no, status, error_message, error_detail,
                           started_at, finished_at
                    FROM step_attempts WHERE execution_id = ?
                    ORDER BY step_index, attempt_no""")) {
                ps.setLong(1, executionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        attempts.add(new StepAttempt(
                                rs.getLong("id"),
                                rs.getInt("step_index"),
                                rs.getString("step_name"),
                                rs.getInt("attempt_no"),
                                rs.getString("status"),
                                rs.getString("error_message"),
                                rs.getString("error_detail"),
                                rs.getObject("started_at", OffsetDateTime.class),
                                rs.getObject("finished_at", OffsetDateTime.class)));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, status, created_at, started_at, finished_at FROM executions WHERE id = ?")) {
                ps.setLong(1, executionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Execution(
                            rs.getLong("id"),
                            rs.getString("status"),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getObject("started_at", OffsetDateTime.class),
                            rs.getObject("finished_at", OffsetDateTime.class),
                            attempts));
                }
            }
        }
    }

    private static long singleLong(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
