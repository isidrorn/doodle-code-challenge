package dev.isidro.queryverb.web;

import static org.springframework.web.servlet.function.RequestPredicates.accept;
import static org.springframework.web.servlet.function.RequestPredicates.contentType;
import static org.springframework.web.servlet.function.RequestPredicates.method;
import static org.springframework.web.servlet.function.RequestPredicates.path;
import static org.springframework.web.servlet.function.RouterFunctions.route;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Functional route definitions (WebMvc.fn).
 *
 * <p>QUERY is routed via {@code HttpMethod.valueOf("QUERY")} — an open value-object
 * in Spring 6, so no custom HandlerMapping or annotation is needed.
 *
 * <pre>
 * GET    /api/users                                         → list users
 * GET    /api/users/{userId}                                → get user
 * POST   /api/users                                         → create user
 *
 * GET    /api/users/{userId}/slots                          → list all slots
 * QUERY  /api/users/{userId}/slots                          → filter slots by body
 * POST   /api/users/{userId}/slots                          → bulk-create slots
 * GET    /api/users/{userId}/slots/{slotId}                 → get slot
 * PATCH  /api/users/{userId}/slots/{slotId}                 → update slot
 * DELETE /api/users/{userId}/slots/{slotId}                 → delete slot
 *
 * POST   /api/meetings                                      → propose a meeting
 * GET    /api/meetings/{meetingId}                          → get meeting
 * DELETE /api/meetings/{meetingId}                          → cancel meeting (organizer only)
 * POST   /api/meetings/{meetingId}/participants/{userId}/vote → cast a vote
 * </pre>
 */
@Configuration
public class SlotRouterConfig {

    private static final HttpMethod QUERY   = HttpMethod.valueOf("QUERY");
    private static final String     USERS   = "/api/users";
    private static final String     USER    = "/api/users/{userId}";
    private static final String     SLOTS   = "/api/users/{userId}/slots";
    private static final String     SLOT    = "/api/users/{userId}/slots/{slotId}";
    private static final String     MEETINGS      = "/api/meetings";
    private static final String     MEETING_BY_ID = "/api/meetings/{meetingId}";
    private static final String     MEETING_VOTE  = "/api/meetings/{meetingId}/participants/{userId}/vote";

    @Bean
    public RouterFunction<ServerResponse> routes(UserHandler users, SlotHandler slots,
                                                  MeetingHandler meetings,
                                                  RouterExceptionFilter exceptionFilter) {
        return route()
                // Users
                .GET(USERS, accept(MediaType.APPLICATION_JSON), users::listAll)
                .GET(USER,  accept(MediaType.APPLICATION_JSON), users::getOne)
                .POST(USERS, contentType(MediaType.APPLICATION_JSON), users::create)
                // Slots
                .GET(SLOTS,  accept(MediaType.APPLICATION_JSON), slots::listAll)
                .route(method(QUERY).and(path(SLOTS)).and(accept(MediaType.APPLICATION_JSON)), slots::query)
                .POST(SLOTS, contentType(MediaType.APPLICATION_JSON), slots::create)
                .GET(SLOT,   accept(MediaType.APPLICATION_JSON), slots::getOne)
                .PATCH(SLOT, contentType(MediaType.APPLICATION_JSON), slots::update)
                .DELETE(SLOT, accept(MediaType.APPLICATION_JSON), slots::delete)
                // Meetings
                .POST(MEETINGS, contentType(MediaType.APPLICATION_JSON), meetings::create)
                .GET(MEETING_BY_ID, accept(MediaType.APPLICATION_JSON), meetings::getOne)
                .DELETE(MEETING_BY_ID, contentType(MediaType.APPLICATION_JSON), meetings::cancel)
                .POST(MEETING_VOTE, contentType(MediaType.APPLICATION_JSON), meetings::vote)
                .build()
                .filter(exceptionFilter::filter);
    }
}
