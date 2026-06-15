package io.orangebuffalo.renalo;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.orangebuffalo.renalo.test.IntegrationTestSupport;
import io.orangebuffalo.renalo.test.TestAuthTokens;
import io.orangebuffalo.renalo.test.TestTimeProvider;
import io.orangebuffalo.renalo.user.PasswordHasher;
import io.orangebuffalo.renalo.user.User;
import io.orangebuffalo.renalo.user.UserRepository;
import io.orangebuffalo.renalo.user.UserType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.orangebuffalo.renalo.test.KotestAssertions.shouldBe;
import static io.orangebuffalo.renalo.test.KotestAssertions.shouldBeNull;
import static io.orangebuffalo.renalo.test.KotestAssertions.shouldBeTrue;

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class LoginPagePlaywrightTest extends IntegrationTestSupport {
    @Inject
    UserRepository userRepository;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    TestTimeProvider testTimeProvider;

    @Inject
    TestAuthTokens testAuthTokens;

    @BeforeEach
    void resetBusinessTime() {
        testTimeProvider.reset();
    }

    @Test
    void logsUserIntoTrackingPage(Page page) {
        saveUser("alice", "password", UserType.USER);

        page.navigate(server.getURL() + "/");
        page.getByLabel("Username").fill("alice");
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")).fill("password");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in")).click();

        var heading = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Expense tracking"));
        assertThat(heading).isVisible();
        assertThat(page.getByText("Signed in as alice")).isVisible();
        assertThat(page.getByRole(AriaRole.NAVIGATION, new Page.GetByRoleOptions().setName("Main navigation"))).isVisible();
        assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Tracking"))).isVisible();
    }

    @Test
    void keepsUserOnTrackingPageAfterRefresh(Page page) {
        saveUser("alice", "password", UserType.USER);

        page.navigate(server.getURL() + "/");
        page.getByLabel("Username").fill("alice");
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")).fill("password");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in")).click();
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Expense tracking"))).isVisible();

        page.reload();

        var heading = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Expense tracking"));
        assertThat(heading).isVisible();
        assertThat(page.getByText("Signed in as alice")).isVisible();
    }

    @Test
    void initializesProfileForDirectRequestedRouteWithStoredToken(Page page) throws Exception {
        saveUser("alice", "password", UserType.USER);
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER));

        page.navigate(server.getURL() + "/tracking");

        var heading = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Expense tracking"));
        assertThat(heading).isVisible();
        assertThat(page.getByText("Signed in as alice")).isVisible();
    }

    @Test
    void showsLoadingContentDuringProfileBootstrap(Page page) throws Exception {
        saveUser("alice", "password", UserType.USER);
        var releaseProfileRequest = new CountDownLatch(1);
        var routeFailure = new AtomicReference<Throwable>();
        ExecutorService profileRequestGate = Executors.newSingleThreadExecutor();
        try {
            page.route("**/api/profile", route -> profileRequestGate.submit(() -> {
                try {
                    if (!releaseProfileRequest.await(10, TimeUnit.SECONDS)) {
                        routeFailure.set(new AssertionError("Timed out waiting to release /api/profile request"));
                    }
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    routeFailure.set(interruptedException);
                } finally {
                    route.resume();
                }
            }));

            page.navigate(server.getURL() + "/");
            page.evaluate("token => window.localStorage.setItem('renalo.authToken', token)", testAuthTokens.issueToken("alice", UserType.USER));
            page.evaluate("setTimeout(() => { window.location.href = '/tracking'; }, 0)");

            try {
                assertThat(page.getByText("Renalo")).isVisible();
                assertThat(page.getByText("Loading your workspace...")).isVisible();
            } finally {
                releaseProfileRequest.countDown();
            }
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Expense tracking")))
                    .isVisible();
            shouldBeNull(routeFailure.get());
        } finally {
            releaseProfileRequest.countDown();
            profileRequestGate.shutdown();
            shouldBeTrue(profileRequestGate.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void redirectsAdminAwayFromTrackingPage(Page page) throws Exception {
        saveUser("admin", "password", UserType.ADMIN);
        setStoredToken(page, testAuthTokens.issueToken("admin", UserType.ADMIN));

        page.navigate(server.getURL() + "/tracking");

        var heading = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("User management"));
        assertThat(heading).isVisible();
        assertThat(page.getByText("Signed in as admin")).isVisible();
    }

    @Test
    void clearsExpiredStoredTokenAndShowsLoginPage(Page page) throws Exception {
        saveUser("alice", "password", UserType.USER);
        testTimeProvider.setNow(Instant.parse("2020-06-14T08:00:00Z"));
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER));

        page.navigate(server.getURL() + "/tracking");

        var heading = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Sign in to Renalo"));
        assertThat(heading).isVisible();
        shouldBeNull(page.evaluate("window.localStorage.getItem('renalo.authToken')"));
    }

    @Test
    void usesClientSideNavigationForMainNavigationLinks(Page page) throws Exception {
        saveUser("alice", "password", UserType.USER);
        setStoredToken(page, testAuthTokens.issueToken("alice", UserType.USER));
        page.navigate(server.getURL() + "/tracking");
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Expense tracking"))).isVisible();

        page.evaluate("window.__renaloNavProbe = 'kept' ");
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Tracking")).click();

        shouldBe(page.evaluate("window.__renaloNavProbe"), "kept");
        var heading = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Expense tracking"));
        assertThat(heading).isVisible();
    }

    @Test
    void logsAdminIntoUserManagementPage(Page page) {
        saveUser("admin", "password", UserType.ADMIN);

        page.navigate(server.getURL() + "/");
        page.getByLabel("Username").fill("admin");
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")).fill("password");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in")).click();

        var heading = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("User management"));
        assertThat(heading).isVisible();
        assertThat(page.getByText("Signed in as admin")).isVisible();
        assertThat(page.getByRole(AriaRole.NAVIGATION, new Page.GetByRoleOptions().setName("Main navigation"))).isVisible();
        assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("User management"))).isVisible();
    }

    @Test
    void showsErrorForWrongPassword(Page page) {
        saveUser("alice", "password", UserType.USER);

        page.navigate(server.getURL() + "/");
        page.getByLabel("Username").fill("alice");
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")).fill("wrong-password");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in")).click();

        assertThat(page.getByText("Invalid username or password.")).isVisible();
        var heading = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Sign in to Renalo"));
        assertThat(heading).isVisible();
    }

    private User saveUser(String username, String password, UserType type) {
        return userRepository.save(new User(null, username, passwordHasher.hash(password), type));
    }

    private void setStoredToken(Page page, String token) {
        page.addInitScript("window.localStorage.setItem('renalo.authToken', '%s');".formatted(token));
    }
}
