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
            hints.resources().registerPattern("scripts/**");
            hints.resources().registerPattern("templates/**");       // ← NEW
            hints.resources().registerPattern("static/**");          // ← NEW
            String[] thymeleafExpressionClasses = {
                    "org.thymeleaf.expression.Strings",
                    "org.thymeleaf.expression.Numbers",
                    "org.thymeleaf.expression.Booleans",
                    "org.thymeleaf.expression.Dates",
                    "org.thymeleaf.expression.Calendars",
                    "org.thymeleaf.expression.Lists",
                    "org.thymeleaf.expression.Sets",
                    "org.thymeleaf.expression.Maps",
                    "org.thymeleaf.expression.Arrays",
                    "org.thymeleaf.expression.Aggregates",
                    "org.thymeleaf.expression.Ids",
                    "org.thymeleaf.expression.Objects",
                    "org.thymeleaf.expression.Messages"
            };
            for (String className : thymeleafExpressionClasses) {
                hints.reflection().registerTypeIfPresent(
                        classLoader,
                        className,
                        MemberCategory.INVOKE_PUBLIC_METHODS);
            }
            hints.reflection().registerType(org.Roclh.model.dto.SubscriptionDto.class,  MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.SubscriptionForm.class, MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.UserDto.class,          MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.EuNodeDto.class,        MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.EuNodeForm.class,       MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.XrayConfigForm.class,   MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.ScriptInfo.class,       MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.EnrollmentRequest.class, MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.EnrollmentResponse.class, MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.NodeHealthRequest.class, MemberCategory.values());

            // java.time — прямые ссылки, registerTypeIfPresent для JDK иногда не срабатывает
            hints.reflection().registerType(
                    java.time.Instant.class,
                    MemberCategory.INVOKE_PUBLIC_METHODS,
                    MemberCategory.INVOKE_DECLARED_METHODS);
            hints.reflection().registerType(
                    java.time.LocalDate.class,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
            hints.reflection().registerType(
                    java.time.LocalDateTime.class,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
            hints.reflection().registerType(
                    java.time.ZonedDateTime.class,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
            hints.reflection().registerType(
                    java.time.OffsetDateTime.class,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
            hints.reflection().registerType(
                    java.time.Duration.class,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
            hints.reflection().registerType(java.time.Instant.class, MemberCategory.values());
            // java.util — если понадобятся в шаблонах
            hints.reflection().registerTypeIfPresent(
                    classLoader, "java.util.UUID", MemberCategory.INVOKE_PUBLIC_METHODS);
            hints.reflection().registerTypeIfPresent(
                    classLoader, "java.util.ArrayList", MemberCategory.INVOKE_PUBLIC_METHODS);
            hints.reflection().registerTypeIfPresent(
                    classLoader, "java.util.HashMap", MemberCategory.INVOKE_PUBLIC_METHODS);
            hints.reflection().registerTypeIfPresent(classLoader, "sun.net.www.protocol.https.Handler",
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            hints.reflection().registerType(InstantStringConverter.class,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            hints.reflection().registerType(
                    org.Roclh.model.dto.EnrollmentRequest.class,
                    MemberCategory.values());
            hints.reflection().registerType(
                    org.Roclh.model.dto.EnrollmentResponse.class,
                    MemberCategory.values());
            hints.reflection().registerType(
                    org.Roclh.model.dto.NodeHealthRequest.class,
                    MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.InviteForm.class,       MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.InviteAcceptForm.class, MemberCategory.values());
            hints.reflection().registerType(
                    org.Roclh.model.dto.XrayClientOption.class,
                    MemberCategory.values());
            hints.reflection().registerType(
                    org.Roclh.model.dto.ClientConfigForm.class,
                    MemberCategory.values());
            hints.reflection().registerType(
                    org.Roclh.model.dto.MetricsSnapshot.class,
                    MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.telegram.TelegramProxyConfigForm.class, MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.telegram.TelegramProxyConfigDto.class, MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.telegram.TelegramProxyUserForm.class, MemberCategory.values());
            hints.reflection().registerType(org.Roclh.model.dto.telegram.TelegramProxyUserDto.class, MemberCategory.values());
            hints.reflection().registerType(
                    org.Roclh.model.dto.XrayConfigPreview.class,
                    MemberCategory.values());
            hints.reflection().registerType(
                    org.Roclh.model.dto.LogFilterOption.class,
                    MemberCategory.values());
            hints.reflection().registerType(
                    org.Roclh.model.dto.ChartSeries.class,
                    MemberCategory.values());
            hints.reflection().registerType(
                    org.Roclh.model.dto.MultiChartDto.class,
                    MemberCategory.values());
        }
    }
}
