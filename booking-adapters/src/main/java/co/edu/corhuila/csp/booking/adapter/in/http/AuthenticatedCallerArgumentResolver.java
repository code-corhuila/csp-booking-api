package co.edu.corhuila.csp.booking.adapter.in.http;

import co.edu.corhuila.csp.booking.application.port.in.AuthenticatedCaller;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * The only place that maps the request attribute the {@link JwtAuthenticationFilter} sets onto the
 * {@link AuthenticatedCaller} of the core: a controller declares the parameter and never reads the
 * attribute itself.
 */
public class AuthenticatedCallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthenticatedCaller.class.equals(parameter.getParameterType());
    }

    @Override
    public AuthenticatedCaller resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                               NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Object subject = request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        if (subject == null) {
            // The filter answers 401 before any controller runs: reaching here is a wiring defect.
            throw new IllegalStateException("no authenticated user on a route that requires one");
        }
        return new AuthenticatedCaller(UUID.fromString((String) subject));
    }
}
