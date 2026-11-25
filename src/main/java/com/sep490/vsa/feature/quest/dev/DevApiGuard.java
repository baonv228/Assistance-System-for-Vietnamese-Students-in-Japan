package com.sep490.vsa.feature.quest.dev;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.regex.Pattern;

/**
 * When the test-only API of "Nhiệm vụ hằng ngày" ({@code /api/dev/**}, contract 5.9) and the sample flash card marks
 * ({@link DevSampleMarks}) exist (contract 3.9): only when BOTH
 * <ul>
 *   <li>{@code app.quests.dev-api.enabled=true} (default false, env {@code QUESTS_DEV_API}), and</li>
 *   <li>the database is on this machine: {@code spring.datasource.url} (and {@code spring.datasource.hikari.jdbc-url}
 *       when that is set, since it would win) names the host {@code localhost}, {@code 127.0.0.1} or {@code [::1]}.</li>
 * </ul>
 * Why the database and not a profile: "local" is the default profile and, without a database of one's own in
 * application-local.properties, it uses the shared server database that the live site senpaioi.com reads (it holds
 * the sample account user@gmail.com too). With the flag on there, the API would reset quests and set the xu and
 * points of real accounts. So the beans are not even registered unless both hold ({@link On}); otherwise
 * {@link DevApiClosedFilter} answers 404 to every {@code /api/dev/**} path ({@link Off}) and logs a WARN at start when
 * only the database kept the API off.
 * <p>
 * The host is read strictly from the address text: {@code jdbc:<driver>://<host>[:<port>][/…]} with exactly one host,
 * without user name, list of hosts, {@code address=(…)} form or driver options such as {@code mysql:loadbalance}.
 * Anything else, an empty or missing address included, counts as not local and the API stays off.
 */
public final class DevApiGuard {

    static final String FLAG = "app.quests.dev-api.enabled";
    static final String URL = "spring.datasource.url";
    /** Hikari's own address, bound onto the pool after spring.datasource.url: checked too when set. */
    static final String HIKARI_URL = "spring.datasource.hikari.jdbc-url";

    /** {@code jdbc:<driver>://<authority>} then the path, query or fragment; the authority is checked on its own. */
    private static final Pattern JDBC = Pattern.compile("(?i)jdbc:[a-z][a-z0-9]*://([^/?#]*)(?:[/?#].*)?",
            Pattern.DOTALL);
    /** The whole authority: one of the three local hosts, an optional port, nothing else. */
    private static final Pattern LOCAL_AUTHORITY = Pattern.compile("(?i)(?:localhost|127\\.0\\.0\\.1|\\[::1])(?::\\d{1,5})?");

    /** Whether the API is on, or why it is off. */
    public enum State {
        ON,
        /** {@code app.quests.dev-api.enabled} is not true. */
        OFF_FLAG,
        /** The flag is on but the database is not on this machine. */
        OFF_DATABASE
    }

    private DevApiGuard() {
    }

    /** True only for a JDBC address of one host that is {@code localhost}, {@code 127.0.0.1} or {@code [::1]}. */
    public static boolean isLocalDatabase(String jdbcUrl) {
        if (jdbcUrl == null) {
            return false;
        }
        var url = JDBC.matcher(jdbcUrl.strip());
        return url.matches() && LOCAL_AUTHORITY.matcher(url.group(1)).matches();
    }

    /** The state for a flag and the database addresses ({@code hikariUrl} null when not set). */
    public static State state(boolean flag, String url, String hikariUrl) {
        if (!flag) {
            return State.OFF_FLAG;
        }
        boolean local = isLocalDatabase(url) && (hikariUrl == null || hikariUrl.isBlank() || isLocalDatabase(hikariUrl));
        return local ? State.ON : State.OFF_DATABASE;
    }

    /** The state of an application, read from its settings as Spring binds them. */
    public static State state(Environment environment) {
        return state(flag(environment), text(environment, URL), text(environment, HIKARI_URL));
    }

    /**
     * A setting as text. One that cannot be read (e.g. a placeholder that cannot be resolved) becomes a text that is
     * no address at all, so it never counts as local, not even as "Hikari address not set".
     */
    private static String text(Environment environment, String key) {
        try {
            return environment.getProperty(key);
        } catch (RuntimeException unreadable) {
            return "unreadable";
        }
    }

    /** The flag as {@code QuestSettings} gets it (env QUESTS_DEV_API, relaxed names); unreadable = off. */
    private static boolean flag(Environment environment) {
        try {
            return Binder.get(environment).bind(FLAG, Bindable.of(Boolean.class)).orElse(false);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** Registers a bean only when the test-only API is on. */
    public static final class On implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return state(context.getEnvironment()) == State.ON;
        }
    }

    /** Registers a bean only when the test-only API is off (for whatever reason). */
    public static final class Off implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return state(context.getEnvironment()) != State.ON;
        }
    }
}
