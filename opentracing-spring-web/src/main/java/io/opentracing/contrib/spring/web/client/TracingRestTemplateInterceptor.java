package io.opentracing.contrib.spring.web.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.MultiValueMap;

import io.opentracing.Scope;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapExtractAdapter;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import io.opentracing.SpanContext;

import io.opentracing.contrib.spring.web.interceptor.HttpServletRequestExtractAdapter;

/**
 * OpenTracing Spring RestTemplate integration. This interceptor creates tracing
 * data for all outgoing requests.
 *
 * @author Pavol Loffay
 */
public class TracingRestTemplateInterceptor implements ClientHttpRequestInterceptor {
    private static final Log log = LogFactory.getLog(TracingRestTemplateInterceptor.class);

    private Tracer tracer;
    private List<RestTemplateSpanDecorator> spanDecorators;
    private SpanContext parentSpanContext;
    private boolean serverDisabled = false;

    public TracingRestTemplateInterceptor() {
        this(GlobalTracer.get(),
                Collections.<RestTemplateSpanDecorator>singletonList(new RestTemplateSpanDecorator.StandardTags()));
    }

    /**
     * @param tracer tracer
     */
    public TracingRestTemplateInterceptor(Tracer tracer) {
        this(tracer,
                Collections.<RestTemplateSpanDecorator>singletonList(new RestTemplateSpanDecorator.StandardTags()));
    }

    /**
     * @param tracer         tracer
     * @param spanDecorators list of decorators
     */
    public TracingRestTemplateInterceptor(Tracer tracer, List<RestTemplateSpanDecorator> spanDecorators) {
        this.tracer = tracer;
        this.spanDecorators = new ArrayList<>(spanDecorators);
    }

    // public TracingRestTemplateInterceptor(Tracer tracer, List<RestTemplateSpanDecorator> spanDecorators, SpanContext sc) {
    //     this.tracer = tracer;
    //     this.spanDecorators = new ArrayList<>(spanDecorators);
    //     this.spanContext = sc;
    // }

    @Override
    public ClientHttpResponse intercept(HttpRequest httpRequest, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse httpResponse;
        System.out.println("*-* Client http req" + httpRequest.getURI().toString() + httpRequest.getMethod());

        MultiValueMap<String, String> rawHeaders22 = httpRequest.getHeaders();
        final HashMap<String, String> headers22 = new HashMap<String, String>();
        for (String key : rawHeaders22.keySet()) {
            headers22.put(key, rawHeaders22.get(key).get(0));
        }
        System.out.println("*-*  Headers now at the beginning of client  " + headers22);


        // toslali: get last active span (ASTRAEA may have disabled some in the middle)
        Scope serverSpan = tracer.scopeManager().active();

        if (serverSpan != null) {
            System.out.println("*-*  ex span " + serverSpan.span());
        } else {
            System.out.println("*-*  server ex span is null ");
        }

        if (serverSpan.span().getBaggageItem("astreaea") != null){
            System.out.println("*-*  Cokemelli " + serverSpan.span().getBaggageItem("astreaea"));
            parentSpanContext = (SpanContext) serverSpan.span().getBaggageItem("astreaea");
            System.out.println("*-*  Cokemelli2 " + parentSpanContext);
            serverSpan.close();
            serverDisabled = true;
            // if astraea is set, that means disable server span (close()) and get spancontext from this bagg item
        }

        


        boolean ASTRAEA = false;
        if (ASTRAEA) { // if disabled by ASTRAEA ; toslali: start the span but inject parent context!!!
            System.out.println("*-*  Dsiabled by ASTRAEA");

            if (serverSpan != null) {
                System.out.println("*-*  server span is here so  injecting");
                // tsl: that  works for disabling client span -- paassing the server span context
                tracer.inject(serverSpan.span().context(), Format.Builtin.HTTP_HEADERS,
                        new HttpHeadersCarrier(httpRequest.getHeaders()));
            } else {
                System.out.println("*-*  server spn is null so injecting REQUESTS INCOMING SPAN ");

                // create scope as child of extracted context and do the same with the below

           

                MultiValueMap<String, String> rawHeaders = httpRequest.getHeaders();
                final HashMap<String, String> headers = new HashMap<String, String>();
                for (String key : rawHeaders.keySet()) {
                    headers.put(key, rawHeaders.get(key).get(0));
                }

                try {
                    // tsl: not working -- parent context is not here
                    SpanContext parentSpanNow = tracer.extract(Format.Builtin.HTTP_HEADERS,
                            new TextMapExtractAdapter(headers));
                    System.out.println("*-*  we have the parent now " + parentSpanNow);
                    tracer.inject(parentSpanNow, Format.Builtin.HTTP_HEADERS,
                            new HttpHeadersCarrier(httpRequest.getHeaders()));

                } catch (IllegalArgumentException e) {
                    // spanBuilder = tracer.buildSpan(operationName);
                    System.out.println("*-* Hatalar");
                    throw e;
                }

            }

            try {
                httpResponse = execution.execute(httpRequest, body);
            } catch (Exception ex) {

                throw ex;
            }

            // }
        } else {
            System.out.println("*-*  Enabled by ASTRAEA");
            SpanContext parentSpan;

            if (serverDisabled) {
                System.out.println("*-*  Server span is disablled so getting span context from bagg");
                // MultiValueMap<String, String> rawHeaders = httpRequest.getHeaders();
                // final HashMap<String, String> headers = new HashMap<String, String>();
                // for (String key : rawHeaders.keySet()) {
                //     headers.put(key, rawHeaders.get(key).get(0));
                // }
                // // System.out.println("*-*  Headers now at client  " + headers);
                // try {
                //     parentSpan = tracer.extract(Format.Builtin.HTTP_HEADERS, new TextMapExtractAdapter(headers));
                //     System.out.println("*-*  yeah we have the parent now " + parentSpan);
                    
                //     // (SpanContext) servletRequest.getAttribute(SERVER_SPAN_CONTEXT)

                // } catch (IllegalArgumentException e) {
                //     // spanBuilder = tracer.buildSpan(operationName);
                //     System.out.println("*-* Hatalar2");
                //     throw e;
                // }

                // create span with parent
                try (Scope scope = tracer.buildSpan(httpRequest.getMethod().toString())
                        // if server span is null then we need extracted context as the parent as there is no active scope
                        // .asChildOf(parentSpan)
                        .asChildOf(parentSpanContext)
                        .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT).startActive(true)) {
                    tracer.inject(scope.span().context(), Format.Builtin.HTTP_HEADERS,
                            new HttpHeadersCarrier(httpRequest.getHeaders()));

                    for (RestTemplateSpanDecorator spanDecorator : spanDecorators) {
                        try {
                            spanDecorator.onRequest(httpRequest, scope.span());
                        } catch (RuntimeException exDecorator) {
                            log.error("Exception during decorating span", exDecorator);
                        }
                    }

                    try {
                        httpResponse = execution.execute(httpRequest, body);
                    } catch (Exception ex) {
                        for (RestTemplateSpanDecorator spanDecorator : spanDecorators) {
                            try {
                                spanDecorator.onError(httpRequest, ex, scope.span());
                            } catch (RuntimeException exDecorator) {
                                log.error("Exception during decorating span", exDecorator);
                            }
                        }
                        throw ex;
                    }

                    for (RestTemplateSpanDecorator spanDecorator : spanDecorators) {
                        try {
                            spanDecorator.onResponse(httpRequest, httpResponse, scope.span());
                        } catch (RuntimeException exDecorator) {
                            log.error("Exception during decorating span", exDecorator);
                        }
                    }
                }

            } else {
                try (Scope scope = tracer.buildSpan(httpRequest.getMethod().toString())
                        .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT).startActive(true)) {
                    tracer.inject(scope.span().context(), Format.Builtin.HTTP_HEADERS,
                            new HttpHeadersCarrier(httpRequest.getHeaders()));

                    for (RestTemplateSpanDecorator spanDecorator : spanDecorators) {
                        try {
                            spanDecorator.onRequest(httpRequest, scope.span());
                        } catch (RuntimeException exDecorator) {
                            log.error("Exception during decorating span", exDecorator);
                        }
                    }

                    try {
                        httpResponse = execution.execute(httpRequest, body);
                    } catch (Exception ex) {
                        for (RestTemplateSpanDecorator spanDecorator : spanDecorators) {
                            try {
                                spanDecorator.onError(httpRequest, ex, scope.span());
                            } catch (RuntimeException exDecorator) {
                                log.error("Exception during decorating span", exDecorator);
                            }
                        }
                        throw ex;
                    }

                    for (RestTemplateSpanDecorator spanDecorator : spanDecorators) {
                        try {
                            spanDecorator.onResponse(httpRequest, httpResponse, scope.span());
                        } catch (RuntimeException exDecorator) {
                            log.error("Exception during decorating span", exDecorator);
                        }
                    }
                }

            }

        }

        return httpResponse;
    }
}
