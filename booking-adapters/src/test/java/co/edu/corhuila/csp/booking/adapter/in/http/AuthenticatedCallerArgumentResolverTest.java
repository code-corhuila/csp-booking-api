package co.edu.corhuila.csp.booking.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.booking.application.port.in.AuthenticatedCaller;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class AuthenticatedCallerArgumentResolverTest {

    private static final String SUB = "11111111-1111-4111-8111-111111111111";

    private final AuthenticatedCallerArgumentResolver resolver = new AuthenticatedCallerArgumentResolver();

    @SuppressWarnings("unused")
    private void handler(AuthenticatedCaller caller, String other) {
    }

    private MethodParameter parameter(int index) throws NoSuchMethodException {
        return new MethodParameter(getClass().getDeclaredMethod("handler", AuthenticatedCaller.class, String.class), index);
    }

    @Test
    void onlyTheAuthenticatedCallerParameterIsResolved() throws Exception {
        assertTrue(resolver.supportsParameter(parameter(0)));
        assertFalse(resolver.supportsParameter(parameter(1)));
    }

    @Test
    void theSubjectTheFilterSetBecomesTheCaller() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, SUB);

        AuthenticatedCaller caller = resolver.resolveArgument(
                parameter(0), null, new ServletWebRequest(request), null);

        assertEquals(UUID.fromString(SUB), caller.userId());
    }

    @Test
    void aRequestWithoutTheAttributeIsAWiringDefect() throws Exception {
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());
        MethodParameter parameter = parameter(0);

        assertThrows(IllegalStateException.class, () -> resolver.resolveArgument(parameter, null, request, null));
    }
}
