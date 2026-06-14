package io.orangebuffalo.renalo;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.orangebuffalo.renalo.test.IntegrationTestSupport;
import io.orangebuffalo.renalo.user.PasswordHasher;
import io.orangebuffalo.renalo.user.User;
import io.orangebuffalo.renalo.user.UserRepository;
import io.orangebuffalo.renalo.user.UserType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@MicronautTest(transactional = false)
@Property(name = "micronaut.server.port", value = "-1")
class LoginPagePlaywrightTest extends IntegrationTestSupport {
    @Inject
    EmbeddedServer server;

    @Inject
    UserRepository userRepository;

    @Inject
    PasswordHasher passwordHasher;

    @Test
    void logsUserIntoTrackingPage(Page page) {
        saveUser("alice", "password", UserType.USER);

        page.navigate(server.getURL() + "/");
        assertLoginInputsAreReadable(page);
        page.getByLabel("Username").fill("alice");
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")).fill("password");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in")).click();

        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Expense tracking"))).isVisible();
        assertThat(page.getByText("Signed in as alice")).isVisible();
        page.waitForTimeout(250);
    }

    @Test
    void logsAdminIntoUserManagementPage(Page page) {
        saveUser("admin", "password", UserType.ADMIN);

        page.navigate(server.getURL() + "/");
        page.getByLabel("Username").fill("admin");
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Password")).fill("password");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in")).click();

        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("User management"))).isVisible();
        assertThat(page.getByText("Signed in as admin")).isVisible();
        page.waitForTimeout(250);
    }

    private void saveUser(String username, String password, UserType type) {
        userRepository.save(new User(null, username, passwordHasher.hash(password), type));
    }

    private void assertLoginInputsAreReadable(Page page) {
        var usernameInput = page.locator("input[name='username']");
        var usernameInputWrapper = usernameInput.locator("xpath=..");

        assertEquals("rgb(16, 24, 40)", usernameInput.evaluate("element => getComputedStyle(element).color"));
        assertEquals("rgb(255, 255, 255)", usernameInputWrapper.evaluate("element => getComputedStyle(element).backgroundColor"));
        assertNotEquals("none", usernameInputWrapper.evaluate("element => getComputedStyle(element).boxShadow"));
    }
}
