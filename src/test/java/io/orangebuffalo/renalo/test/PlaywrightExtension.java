package io.orangebuffalo.renalo.test;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Clock;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.util.Date;

public class PlaywrightExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(PlaywrightExtension.class);
    private static final java.nio.file.Path TRACES_DIR = java.nio.file.Paths.get("build", "playwright-traces");

    @Override
    public void beforeEach(ExtensionContext context) {
        if (!requiresPlaywrightPage(context)) {
            return;
        }

        Playwright playwright = Playwright.create();
        Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        BrowserContext browserContext = browser.newContext();
        browserContext.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));
        Page page = browserContext.newPage();
        page.clock().install(new Clock.InstallOptions().setTime(Date.from(TestTimeProvider.DEFAULT_TIME)));

        ExtensionContext.Store store = context.getStore(NAMESPACE);
        store.put(Playwright.class, playwright);
        store.put(Browser.class, browser);
        store.put(BrowserContext.class, browserContext);
        store.put(Page.class, page);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        store.remove(Page.class);
        BrowserContext browserContext = store.remove(BrowserContext.class, BrowserContext.class);
        Browser browser = store.remove(Browser.class, Browser.class);
        Playwright playwright = store.remove(Playwright.class, Playwright.class);

        if (browserContext != null) {
            stopTracing(context, browserContext);
            browserContext.close();
        }
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType().equals(Page.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return extensionContext.getStore(NAMESPACE).get(Page.class, Page.class);
    }

    private void stopTracing(ExtensionContext context, BrowserContext browserContext) {
        try {
            java.nio.file.Files.createDirectories(TRACES_DIR);
            java.nio.file.Path tracePath = TRACES_DIR.resolve(traceFileName(context));
            browserContext.tracing().stop(new Tracing.StopOptions().setPath(tracePath));
            System.out.println("Playwright trace saved to " + tracePath.toAbsolutePath());
        } catch (Exception traceFailure) {
            throw new RuntimeException("Failed to save Playwright trace", traceFailure);
        }
    }

    private String traceFileName(ExtensionContext context) {
        String testName = context.getRequiredTestClass().getSimpleName() + "-" + context.getRequiredTestMethod().getName();
        return testName.replaceAll("[^A-Za-z0-9._-]", "-") + ".zip";
    }

    private boolean requiresPlaywrightPage(ExtensionContext context) {
        for (Class<?> parameterType : context.getRequiredTestMethod().getParameterTypes()) {
            if (parameterType.equals(Page.class)) {
                return true;
            }
        }
        return false;
    }
}
