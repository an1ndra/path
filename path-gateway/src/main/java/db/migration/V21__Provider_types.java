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
        // Filter on the clause, not just the constraint type. Postgres 16 reports NOT NULL
        // as a CHECK constraint in information_schema.table_constraints with a synthetic
        // name that cannot be dropped (ERROR: constraint "..._not_null" does not exist),
        // which failed this migration and crash-looped the gateway. Only the type check
        // mentions OPENAI.
        String sql = "select tc.constraint_name "
                + "from information_schema.table_constraints tc "
                + "join information_schema.check_constraints cc "
                + "  on cc.constraint_name = tc.constraint_name "
                + " and cc.constraint_schema = tc.constraint_schema "
                + "where lower(tc.table_name) = '" + TABLE + "' "
                + "  and tc.constraint_type = 'CHECK' "
                + "  and cc.check_clause like '%OPENAI%'";
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
