package io.orangebuffalo.renalo;

import com.microsoft.playwright.Locator;
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

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.orangebuffalo.renalo.test.KotestAssertions.shouldBeNull;

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class UserManagementPagePlaywrightTest extends IntegrationTestSupport {
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
    void managesUsersFromAdminPage(Page page) throws Exception {
        User admin = saveUser("admin", "password", UserType.ADMIN);
        User alice = saveUser("alice", "password", UserType.USER);
        saveUser("bob", "password", UserType.USER);
        saveUser("charlie", "password", UserType.USER);
        saveUser("dana", "password", UserType.USER);
        User erin = saveUser("erin", "password", UserType.USER);
        setStoredToken(page, testAuthTokens.issueToken("admin", UserType.ADMIN));

        page.navigate(server.getURL() + "/user-management");

        assertThat(page.getByRole(AriaRole.GRID, new Page.GetByRoleOptions().setName("Users"))).isVisible();
        Locator adminRow = userRow(page, admin);
        assertThat(adminRow).containsText("admin");
        assertThat(adminRow).containsText("ADMIN");
        assertThat(adminRow.getByText("Current user")).isVisible();
        assertThat(page.getByRole(AriaRole.NAVIGATION, new Page.GetByRoleOptions().setName("Pagination Navigation"))).isVisible();

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Next")).click();
        assertThat(userRow(page, erin)).isVisible();

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Previous")).click();
        Locator aliceRow = userRow(page, alice);
        assertThat(aliceRow).isVisible();
        aliceRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Remove")).click();

        assertThat(page.getByRole(AriaRole.DIALOG, new Page.GetByRoleOptions().setName("Remove alice?"))).isVisible();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Remove user")).click();

        assertThat(aliceRow).isHidden();
        shouldBeNull(userRepository.findByUsername("alice"));
    }

    private User saveUser(String username, String password, UserType type) {
        return userRepository.save(new User(null, username, passwordHasher.hash(password), type));
    }

    private void setStoredToken(Page page, String token) {
        page.addInitScript("window.localStorage.setItem('renalo.authToken', '%s');".formatted(token));
    }

    private Locator userRow(Page page, User user) {
        return page.locator("[data-testid='user-row-%d']".formatted(user.getId()));
    }
}
