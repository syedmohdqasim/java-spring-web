package io.opentracing.contrib.spring.web.interceptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import io.opentracing.References;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.contrib.web.servlet.filter.TracingFilter;



import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;

/**
 * Tracing handler interceptor for spring web. It creates a new span for an incoming request
 * if there is no active request and a separate span for Spring's exception handling.
 * This handler depends on {@link TracingFilter}. Both classes have to be properly configured.
 *
 * <p>HTTP tags and logged errors are added in {@link TracingFilter}. This interceptor adds only
 * spring related logs (handler class/method).
 *
 * @author Pavol Loffay
 */
public class TracingHandlerInterceptor extends HandlerInterceptorAdapter {

    private static final String SCOPE_STACK = TracingHandlerInterceptor.class.getName() + ".scopeStack";
    private static final String CONTINUATION_FROM_ASYNC_STARTED = TracingHandlerInterceptor.class.getName() + ".continuation";

    private Tracer tracer;
    private List<HandlerInterceptorSpanDecorator> decorators;

    /**
     * @param tracer
     */
    public TracingHandlerInterceptor(Tracer tracer) {
        this(tracer, Arrays.asList(HandlerInterceptorSpanDecorator.STANDARD_LOGS,
                HandlerInterceptorSpanDecorator.HANDLER_METHOD_OPERATION_NAME));
    }

    /**
     * @param tracer tracer
     * @param decorators span decorators
     */
    public TracingHandlerInterceptor(Tracer tracer, List<HandlerInterceptorSpanDecorator> decorators) {
        this.tracer = tracer;
        this.decorators = new ArrayList<>(decorators);
    }

    /**
     * This method determines whether the HTTP request is being traced.
     *
     * @param httpServletRequest The HTTP request
     * @return Whether the request is being traced
     */
    static boolean isTraced(HttpServletRequest httpServletRequest) {
        // exclude pattern, span is not started in filter
        return httpServletRequest.getAttribute(TracingFilter.SERVER_SPAN_CONTEXT) instanceof SpanContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object handler)
            throws Exception {

        if (!isTraced(httpServletRequest)) {
            return true;
        }

        /*
         * 1. check if there is an active span, it has been activated in servlet filter or in this interceptor (forward)
         * 2. if there is no active span then it can be handling of an async request or spring boot default error handling
         */
        Scope serverSpan = tracer.scopeManager().active();
    // System.out.println("*-* Pre handle server span" + serverSpan);
    System.out.println("*-*  Pre handle ex span" + serverSpan.span());

	String opName =  handler instanceof HandlerMethod ?
                    ((HandlerMethod) handler).getMethod().getName() : null;
    System.out.println("*-* Operation name for the current span" +opName);
    
// tsl: aSTRAEA trial for specific operation name
        if (opName.equalsIgnoreCase("getRouteByTripId")){
            System.out.println("*-* Do not create soan for this");
            // serverSpan 
            // tracer.scopeManager()
            // serverSpan.
            // tracer.inject(serverSpan.span().context(), Format.Builtin.HTTP_HEADERS, new HttpHeadersCarrier(httpRequest.getHeaders()));
            serverSpan = tracer.buildSpan("mert")
            .addReference(References.FOLLOWS_FROM, serverSpan.span().context())
            .startActive(true);

        }

        else{
            if (serverSpan == null) {
                System.out.println("*-* Null olmustu ");
                if (httpServletRequest.getAttribute(CONTINUATION_FROM_ASYNC_STARTED) != null) {
                    Span contd = (Span) httpServletRequest.getAttribute(CONTINUATION_FROM_ASYNC_STARTED);
                    serverSpan = tracer.scopeManager().activate(contd, false);
                    httpServletRequest.removeAttribute(CONTINUATION_FROM_ASYNC_STARTED);
                } else {
                    // spring boot default error handling, executes interceptor after processing in the filter (ugly huh?)
            System.out.println("*-* Prehandle Client http req" + httpServletRequest.getRequestURI().toString() + httpServletRequest.getMethod());
    
                    serverSpan = tracer.buildSpan(httpServletRequest.getMethod())
                            .addReference(References.FOLLOWS_FROM, TracingFilter.serverSpanContext(httpServletRequest))
                            .startActive(true);
    
                    // if (opName.equalsIgnoreCase("getRouteByTripId")){
                    //     System.out.println("*-* Do not create soan for this");
                    // }
                    // else{
                        
                    //     System.out.println("*-* Creating the new span now");
                    //     SpanContext extractedContext = tracer.extract(Format.Builtin.HTTP_HEADERS,
                    //     new HttpServletRequestExtractAdapter(httpServletRequest));
    
                    //     serverSpan = tracer.buildSpan(opName)
                    //     .asChildOf(extractedContext)
                    //     // .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
                    //     .startActive(true);
    
                    // }
    
    
                }
                Deque<Scope> activeSpanStack = getScopeStack(httpServletRequest);
                activeSpanStack.push(serverSpan);
            }
    
            for (HandlerInterceptorSpanDecorator decorator : decorators) {
                decorator.onPreHandle(httpServletRequest, handler, serverSpan.span());
            }
        }

        

        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted (
            HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object handler)
            throws Exception {

                System.out.println("*-* 111");

        if (!isTraced(httpServletRequest)) {
            return;
        }

        Span span;
        Deque<Scope> activeSpanStack = getScopeStack(httpServletRequest);
        if(activeSpanStack.size() > 0) {
            Scope scope = activeSpanStack.pop();
            span = scope.span();
            onAfterConcurrentHandlingStarted(httpServletRequest, httpServletResponse, handler, span);
            scope.close();
        } else {
            span = tracer.activeSpan();
            onAfterConcurrentHandlingStarted(httpServletRequest, httpServletResponse, handler, span);
        }

        httpServletRequest.setAttribute(CONTINUATION_FROM_ASYNC_STARTED, span);

    }

    private void onAfterConcurrentHandlingStarted(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object handler, Span span) {
        for (HandlerInterceptorSpanDecorator decorator : decorators) {
            decorator.onAfterConcurrentHandlingStarted(httpServletRequest, httpServletResponse, handler, span);
        }
        System.out.println("*-* 222");
    }

    @Override
    public void afterCompletion(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
                                Object handler, Exception ex) throws Exception {

                                    System.out.println("*-* 333");
        if (!isTraced(httpServletRequest)) {
            return;
        }

        Deque<Scope> scopeStack = getScopeStack(httpServletRequest);
        if(scopeStack.size() > 0) {
            Scope scope = scopeStack.pop();
            onAfterCompletion(httpServletRequest, httpServletResponse, handler, ex, scope.span());
            scope.close();
        } else {
            onAfterCompletion(httpServletRequest, httpServletResponse, handler, ex, tracer.activeSpan());
        }
    }

    private void onAfterCompletion(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
                                   Object handler, Exception ex, Span span) {
                                    System.out.println("*-* 444");
        for (HandlerInterceptorSpanDecorator decorator : decorators) {
            decorator.onAfterCompletion(httpServletRequest, httpServletResponse, handler, ex, span);
        }
    }

    private Deque<Scope> getScopeStack(HttpServletRequest request) {
        Deque<Scope> stack = (Deque<Scope>) request.getAttribute(SCOPE_STACK);
        if (stack == null) {
            stack = new ArrayDeque<>();
            request.setAttribute(SCOPE_STACK, stack);
        }
        return stack;
    }
}
