package org.keycloak.adapters.undertow;

import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.SessionManager;
import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.StatusCodes;
import org.jboss.logging.Logger;
import org.keycloak.adapters.AdapterConstants;
import org.keycloak.adapters.KeycloakDeployment;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.jose.jws.crypto.RSAProvider;
import org.keycloak.representations.adapters.action.AdminAction;
import org.keycloak.representations.adapters.action.PushNotBeforeAction;
import org.keycloak.representations.adapters.action.SessionStats;
import org.keycloak.representations.adapters.action.SessionStatsAction;
import org.keycloak.representations.adapters.action.UserStats;
import org.keycloak.representations.adapters.action.UserStatsAction;
import org.keycloak.util.JsonSerialization;
import org.keycloak.util.StreamUtil;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class ServletAdminActionsHandler implements HttpHandler {

    private static final Logger log = Logger.getLogger(ServletAdminActionsHandler.class);
    protected HttpHandler next;
    protected UserSessionManagement userSessionManagement;
    protected KeycloakDeployment deployment;

    public static class Wrapper implements HandlerWrapper {
        protected KeycloakDeployment deployment;
        protected UserSessionManagement userSessionManagement;


        public Wrapper(KeycloakDeployment deployment, UserSessionManagement userSessionManagement) {
            this.deployment = deployment;
            this.userSessionManagement = userSessionManagement;
        }

        @Override
        public HttpHandler wrap(HttpHandler handler) {
            return new ServletAdminActionsHandler(deployment, userSessionManagement, handler);
        }
    }

    protected ServletAdminActionsHandler(KeycloakDeployment deployment,
                                         UserSessionManagement userSessionManagement,
                                         HttpHandler next) {
        this.next = next;
        this.deployment = deployment;
        this.userSessionManagement = userSessionManagement;
    }





    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        log.debugv("adminActions {0}", exchange.getRequestURI());
        final ServletRequestContext servletRequestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
        HttpServletRequest request = (HttpServletRequest) servletRequestContext.getServletRequest();
        HttpServletResponse response = (HttpServletResponse) servletRequestContext.getServletResponse();
        SessionManager manager = servletRequestContext.getDeployment().getSessionManager();
        String requestUri = exchange.getRequestURI();
        if (requestUri.endsWith(AdapterConstants.K_LOGOUT)) {
            log.info("K_LOGOUT sent");
            JWSInput token = verifyAdminRequest(request, response);
            if (token == null) return;
            userSessionManagement.remoteLogout(token, manager, response);
            return;
        } else if (requestUri.endsWith(AdapterConstants.K_PUSH_NOT_BEFORE)) {
            handlePushNotBefore(request, response);
            return;
        } else if (requestUri.endsWith(AdapterConstants.K_GET_SESSION_STATS)) {
            handleGetSessionStats(request, response);
            return;
        }else if (requestUri.endsWith(AdapterConstants.K_GET_USER_STATS)) {
            handleGetUserStats(request, response);
            return;
        } else {
            next.handleRequest(exchange);
            return;
        }
    }

    protected void handlePushNotBefore(HttpServletRequest request, HttpServletResponse response) throws Exception {
        log.info("K_PUSH_NOT_BEFORE sent");
        JWSInput token = verifyAdminRequest(request, response);
        if (token == null) {
            return;
        }
        PushNotBeforeAction action = JsonSerialization.readValue(token.getContent(), PushNotBeforeAction.class);
        if (!validateAction(response, action)) return;
        deployment.setNotBefore(action.getNotBefore());
        return;
    }

    protected JWSInput verifyAdminRequest(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String token = StreamUtil.readString(request.getInputStream());
        if (token == null) {
            log.warn("admin request failed, no token");
            response.sendError(StatusCodes.FORBIDDEN, "no token");
            return null;
        }

        JWSInput input = new JWSInput(token);
        boolean verified = false;
        try {
            verified = RSAProvider.verify(input, deployment.getRealmKey());
        } catch (Exception ignore) {
        }
        if (!verified) {
            log.warn("admin request failed, unable to verify token");
            response.sendError(StatusCodes.FORBIDDEN, "verification failed");
            return null;
        }
        return input;
    }


    protected boolean validateAction(HttpServletResponse response, AdminAction action) throws IOException {
        if (!action.validate()) {
            log.warn("admin request failed, not validated" + action.getAction());
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Not validated");
            return false;
        }
        if (action.isExpired()) {
            log.warn("admin request failed, expired token");
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Expired token");
            return false;
        }
        if (!deployment.getResourceName().equals(action.getResource())) {
            log.warn("Resource name does not match");
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Resource name does not match");
            return false;

        }
        return true;
    }

    protected void handleGetSessionStats(HttpServletRequest request, HttpServletResponse response) throws Exception {
        log.info("K_GET_SESSION_STATS sent");
        JWSInput token = verifyAdminRequest(request, response);
        if (token == null) return;
        SessionStatsAction action = JsonSerialization.readValue(token.getContent(), SessionStatsAction.class);
        if (!validateAction(response, action)) return;
        SessionStats stats = new SessionStats();
        stats.setActiveSessions(userSessionManagement.getActiveSessions());
        stats.setActiveUsers(userSessionManagement.getActiveUsers().size());
        if (action.isListUsers() && userSessionManagement.getActiveSessions() > 0) {
            Map<String, UserStats> list = new HashMap<String, UserStats>();
            for (String user : userSessionManagement.getActiveUsers()) {
                list.put(user, getUserStats(user));
            }
            stats.setUsers(list);
        }
        response.setStatus(200);
        response.setContentType("application/json");
        JsonSerialization.writeValueToStream(response.getOutputStream(), stats);
        return;
    }
    protected void handleGetUserStats(HttpServletRequest request, HttpServletResponse response) throws Exception {
        log.info("K_GET_USER_STATS sent");
        JWSInput token = verifyAdminRequest(request, response);
        if (token == null) return;
        UserStatsAction action = JsonSerialization.readValue(token.getContent(), UserStatsAction.class);
        if (!validateAction(response, action)) return;
        String user = action.getUser();
        UserStats stats = getUserStats(user);
        response.setStatus(200);
        response.setContentType("application/json");
        JsonSerialization.writeValueToStream(response.getOutputStream(), stats);
        return;
    }

    protected UserStats getUserStats(String user) {
        UserStats stats = new UserStats();
        Long loginTime = userSessionManagement.getUserLoginTime(user);
        if (loginTime != null) {
            stats.setLoggedIn(true);
            stats.setWhenLoggedIn(loginTime);
        } else {
            stats.setLoggedIn(false);
        }
        return stats;
    }
}
