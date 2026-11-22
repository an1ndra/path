package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Widens the provider `type` check constraint to the wire formats the code supports.
 *
 * V1 only knew OPENAI and ANTHROPIC; v0.2.3 added GEMINI and VERTEX to the enum without a
 * migration, so creating either provider failed with a constraint violation on Postgres
 * and H2 alike (the admin API answered 500). The constraint's name depends on the
 * database (Postgres derives `providers_type_check`, H2 generates `CONSTRAINT_n`), so the
 * migration finds it through information_schema instead of guessing.
 */
public class V21__Provider_types extends BaseJavaMigration {

    private static final String TABLE = "providers";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        for (String name : checkConstraints(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("alter table " + TABLE + " drop constraint " + quote(name));
            }
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table " + TABLE + " add constraint providers_type_check "
                    + "check (type in ('OPENAI', 'ANTHROPIC', 'GEMINI', 'VERTEX'))");
        }
    }

    private List<String> checkConstraints(Connection connection) throws Exception {
        List<String> names = new ArrayList<>();
        String sql = "select constraint_name from information_schema.table_constraints "
                + "where lower(table_name) = '" + TABLE + "' and constraint_type = 'CHECK'";
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    /** Identifiers are read from the catalog, not user input; still quote them safely. */
    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
