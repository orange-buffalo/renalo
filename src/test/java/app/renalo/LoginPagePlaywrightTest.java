package app.renalo;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
class LoginPagePlaywrightTest {
    @Inject
    EmbeddedServer server;

    @Test
    void rendersLoginControls() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();

            page.navigate(server.getURL() + "/");

            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Sign in to Renalo"))).isVisible();
            assertThat(page.getByLabel("Username")).isVisible();
            assertThat(page.getByLabel("Password")).isVisible();
            assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in"))).isVisible();
        }
    }
}
