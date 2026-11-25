package com.sep490.vsa.feature.quest.dev;

import com.sep490.vsa.feature.quest.dev.DevApiGuard.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * When the test-only API exists (contract 3.9): the flag AND a database on this machine, read strictly from the JDBC
 * address. No database and no Spring application here: the rule itself, its conditions and what a context gets.
 */
@ExtendWith(OutputCaptureExtension.class)
class DevApiGuardTest {

    private static final String LOCAL = "jdbc:mysql://localhost:3306/asvsj?useUnicode=true&characterEncoding=utf8";
    private static final String SHARED = "jdbc:mysql://103.140.249.78:3306/asvsj?useUnicode=true&characterEncoding=utf8"
            + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=REQUIRED";

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://localhost:3306/asvsj_questdev_test?createDatabaseIfNotExist=true&useUnicode=true",
            "jdbc:mysql://localhost/x",
            "jdbc:mysql://localhost",
            "jdbc:mysql://127.0.0.1/x",
            "jdbc:mysql://127.0.0.1:3307/x?sslMode=DISABLED",
            "jdbc:mysql://[::1]:3306/x",
            "jdbc:mysql://[::1]/x",
            "JDBC:MYSQL://LocalHost:3306/x",
            "  jdbc:mysql://localhost:3306/x  ",
            "jdbc:mariadb://localhost:3306/x"})
    void addressesOnThisMachineAreLocal(String url) {
        assertThat(DevApiGuard.isLocalDatabase(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            SHARED,
            "jdbc:mysql://103.140.249.78/asvsj",
            "jdbc:mysql://localhost.evil.com/x",
            "jdbc:mysql://localhost.evil.com:3306/x",
            "jdbc:mysql://evillocalhost/x",
            "jdbc:mysql://localhost-db/x",
            "jdbc:mysql://evil.com/localhost",
            "jdbc:mysql://evil.com:3306/x?host=localhost",
            "jdbc:mysql://127.0.0.10/x",
            "jdbc:mysql://127.0.0.1.nip.io/x",
            "jdbc:mysql://[::2]/x",
            "jdbc:mysql://[::1/x",
            "jdbc:mysql://::1/x",
            "jdbc:mysql://user:secret@localhost/x",
            "jdbc:mysql://localhost@103.140.249.78/x",
            "jdbc:mysql://localhost:3306,103.140.249.78:3306/x",
            "jdbc:mysql://[localhost,103.140.249.78]/x",
            "jdbc:mysql://address=(host=localhost)(port=3306)/x",
            "jdbc:mysql:loadbalance://localhost,103.140.249.78/x",
            "jdbc:mysql:replication://localhost/x",
            "jdbc:mysql+srv://localhost/x",
            "jdbc:mysql:///x",
            "jdbc:mysql://:3306/x",
            "jdbc:mysql://localhost:/x",
            "jdbc:mysql:// localhost/x",
            "jdbc:mysql://localhost\\@evil.com/x",
            "jdbc:mysql://localhost%2Eevil.com/x",
            "mysql://localhost/x",
            "localhost",
            "jdbc:h2:mem:test",
            "not an address",
            "",
            "   "})
    void everyOtherAddressIsNotLocal(String url) {
        assertThat(DevApiGuard.isLocalDatabase(url)).isFalse();
    }

    @Test
    void noAddressIsNotLocal() {
        assertThat(DevApiGuard.isLocalDatabase(null)).isFalse();
    }

    @Test
    void bothTheFlagAndALocalDatabaseAreNeeded() {
        assertThat(DevApiGuard.state(false, LOCAL, null)).isEqualTo(State.OFF_FLAG);
        assertThat(DevApiGuard.state(false, SHARED, null)).isEqualTo(State.OFF_FLAG);
        assertThat(DevApiGuard.state(true, SHARED, null)).isEqualTo(State.OFF_DATABASE);
        assertThat(DevApiGuard.state(true, null, null)).isEqualTo(State.OFF_DATABASE);
        assertThat(DevApiGuard.state(true, "", null)).isEqualTo(State.OFF_DATABASE);
        assertThat(DevApiGuard.state(true, LOCAL, null)).isEqualTo(State.ON);
        // Hikari's own address would win over spring.datasource.url: it must be local too when it is set
        assertThat(DevApiGuard.state(true, LOCAL, " ")).isEqualTo(State.ON);
        assertThat(DevApiGuard.state(true, LOCAL, "jdbc:mysql://127.0.0.1/x")).isEqualTo(State.ON);
        assertThat(DevApiGuard.state(true, LOCAL, SHARED)).isEqualTo(State.OFF_DATABASE);
    }

    @Test
    void theSettingsAreReadAsSpringBindsThem() {
        // As in application.properties: the flag comes from QUESTS_DEV_API, false when it is not set
        MockEnvironment placeholder = new MockEnvironment()
                .withProperty(DevApiGuard.FLAG, "${QUESTS_DEV_API:false}").withProperty(DevApiGuard.URL, LOCAL);
        assertThat(DevApiGuard.state(placeholder)).isEqualTo(State.OFF_FLAG);
        placeholder.setProperty("QUESTS_DEV_API", "true");
        assertThat(DevApiGuard.state(placeholder)).isEqualTo(State.ON);

        assertThat(DevApiGuard.state(new MockEnvironment().withProperty(DevApiGuard.URL, LOCAL)))
                .isEqualTo(State.OFF_FLAG);
        assertThat(DevApiGuard.state(new MockEnvironment().withProperty(DevApiGuard.FLAG, "TRUE")
                .withProperty(DevApiGuard.URL, LOCAL))).isEqualTo(State.ON);
        assertThat(DevApiGuard.state(new MockEnvironment().withProperty(DevApiGuard.FLAG, "maybe")
                .withProperty(DevApiGuard.URL, LOCAL))).isEqualTo(State.OFF_FLAG);
        assertThat(DevApiGuard.state(new MockEnvironment().withProperty(DevApiGuard.FLAG, "true")
                .withProperty(DevApiGuard.URL, SHARED))).isEqualTo(State.OFF_DATABASE);
        assertThat(DevApiGuard.state(new MockEnvironment().withProperty(DevApiGuard.FLAG, "true")))
                .isEqualTo(State.OFF_DATABASE);
        assertThat(DevApiGuard.state(new MockEnvironment().withProperty(DevApiGuard.FLAG, "true")
                .withProperty(DevApiGuard.URL, "${NO_SUCH_SETTING}"))).isEqualTo(State.OFF_DATABASE);
        assertThat(DevApiGuard.state(new MockEnvironment().withProperty(DevApiGuard.FLAG, "true")
                .withProperty(DevApiGuard.URL, LOCAL).withProperty(DevApiGuard.HIKARI_URL, SHARED)))
                .isEqualTo(State.OFF_DATABASE);
    }

    @Test
    void theConditionsAreOppositeAndFollowTheState() {
        assertThat(conditions(new MockEnvironment().withProperty(DevApiGuard.FLAG, "true")
                .withProperty(DevApiGuard.URL, LOCAL))).containsExactly(true, false);
        assertThat(conditions(new MockEnvironment().withProperty(DevApiGuard.FLAG, "false")
                .withProperty(DevApiGuard.URL, LOCAL))).containsExactly(false, true);
        assertThat(conditions(new MockEnvironment().withProperty(DevApiGuard.FLAG, "true")
                .withProperty(DevApiGuard.URL, SHARED))).containsExactly(false, true);
    }

    private static Boolean[] conditions(MockEnvironment environment) {
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return new Boolean[] {new DevApiGuard.On().matches(context, null), new DevApiGuard.Off().matches(context, null)};
    }

    @Test
    void flagOnWithTheSharedDatabaseRegistersNoApiAndWarnsOnce(CapturedOutput output) {
        contextOf(true, SHARED).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DevQuestController.class);
            assertThat(context).doesNotHaveBean(DevQuestService.class);
            assertThat(context).doesNotHaveBean(DevSampleMarks.class);
            assertThat(context).hasSingleBean(DevApiClosedFilter.class);
        });
        String warning = "stay OFF although app.quests.dev-api.enabled=true";
        assertThat(StringUtils.countOccurrencesOf(output.getOut(), warning)).isEqualTo(1);
        assertThat(output.getOut()).doesNotContain("103.140.249.78");
    }

    @Test
    void flagOffRegistersNoApiAndSaysNothing(CapturedOutput output) {
        contextOf(false, LOCAL).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DevQuestController.class);
            assertThat(context).doesNotHaveBean(DevQuestService.class);
            assertThat(context).doesNotHaveBean(DevSampleMarks.class);
            assertThat(context).hasSingleBean(DevApiClosedFilter.class);
        });
        assertThat(output.getOut()).doesNotContain("stay OFF");
    }

    @Test
    void flagOnWithALocalDatabaseHasNoClosedFilter() {
        new ApplicationContextRunner()
                .withPropertyValues(DevApiGuard.FLAG + "=true", DevApiGuard.URL + "=" + LOCAL)
                .withUserConfiguration(DevApiClosedFilter.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DevApiClosedFilter.class);
                });
    }

    /** The API's classes registered as they are scanned; their conditions decide which ones exist. */
    private static ApplicationContextRunner contextOf(boolean flag, String url) {
        return new ApplicationContextRunner()
                .withPropertyValues(DevApiGuard.FLAG + "=" + flag, DevApiGuard.URL + "=" + url)
                .withUserConfiguration(DevApiClosedFilter.class, DevQuestController.class, DevQuestService.class,
                        DevSampleMarks.class);
    }
}
