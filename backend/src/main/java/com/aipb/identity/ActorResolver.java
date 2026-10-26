package com.aipb.identity;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Supplies an {@link Actor} parameter to controller methods, from the X-AIPB-Role header (default: Banker). */
public class ActorResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-AIPB-Role";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Actor.class.equals(parameter.getParameterType());
    }

    @Override
    public Actor resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                 WebDataBinderFactory binders) {
        return resolve(request.getHeader(HEADER));
    }

    public static Actor resolve(String header) { return Actor.of(Role.parse(header)); }
}
