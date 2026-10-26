package fr.vertexvelocity.webpanel.spring;

import fr.vertexvelocity.webpanel.web.WebPanelServer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Routes all browser/API requests through the existing domain handlers, hosted by Spring MVC. */
@RestController
public final class PanelWebController {
    private final WebPanelServer delegate;

    public PanelWebController() {
        this.delegate = PanelWebApplication.getDelegate();
    }

    @RequestMapping(value = {"/", "/**"})
    public void dispatch(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ServletHttpExchangeAdapter exchange = new ServletHttpExchangeAdapter(request);
        try {
            delegate.handleExchange(exchange);
            exchange.writeResponseTo(response);
        } catch (Exception exception) {
            if (!response.isCommitted()) {
                response.resetBuffer();
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"Internal server error\"}");
            }
        }
    }
}
