package org.Roclh.config;

import org.Roclh.domain.InstantStringConverter;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

@Configuration
@ImportRuntimeHints(NativeHints.SqliteHints.class)
public class NativeHints {
    static class SqliteHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
            hints.reflection().registerTypeIfPresent(
                    classLoader,
                    "org.sqlite.JDBC",
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS
            );
            // Hibernate-диалект — загружается через Class.forName
            hints.reflection().registerTypeIfPresent(
                    classLoader,
                    "org.hibernate.community.dialect.SQLiteDialect",
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS
            );
            hints.reflection().registerTypeIfPresent(classLoader,
                    "liquibase.parser.core.sql.SqlChangeLogParser",
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            hints.reflection().registerTypeIfPresent(classLoader,
                    "liquibase.parser.core.formattedsql.FormattedSqlChangeLogParser",
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            // Liquibase changelog-парсеры (если упадёт — добавим точечно)
            hints.resources().registerPattern("db/changelog/**");
            hints.resources().registerPattern("i18n/messages*");
            hints.reflection().registerTypeIfPresent(classLoader, "sun.net.www.protocol.https.Handler",
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            hints.reflection().registerType(InstantStringConverter.class,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        }
    }
}
