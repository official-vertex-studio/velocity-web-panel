package fr.vertexvelocity.webpanel.spring;

import fr.vertexvelocity.webpanel.web.WebPanelServer;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.atomic.AtomicReference;

/** Embedded Spring Boot application hosted inside the Velocity plugin lifecycle. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(PanelWebController.class)
public class PanelWebApplication {
    private static final AtomicReference<WebPanelServer> DELEGATE = new AtomicReference<>();

    public static void setDelegate(WebPanelServer delegate) {
        DELEGATE.set(delegate);
    }

    public static WebPanelServer getDelegate() {
        WebPanelServer delegate = DELEGATE.get();
        if (delegate == null) throw new IllegalStateException("Velocity web panel delegate has not been initialized");
        return delegate;
    }

    public static void clearDelegate(WebPanelServer delegate) {
        DELEGATE.compareAndSet(delegate, null);
    }
}
