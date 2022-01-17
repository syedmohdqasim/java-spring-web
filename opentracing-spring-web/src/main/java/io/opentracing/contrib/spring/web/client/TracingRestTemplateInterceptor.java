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
import io.opentracing.propagation.TextMap;
// import io.opentracing.propagation.TextMapAdapter;
import io.opentracing.propagation.TextMapExtractAdapter;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import io.opentracing.SpanContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import java.util.Map;

import io.opentracing.contrib.spring.web.interceptor.HttpServletRequestExtractAdapter;

import java.io.File;  // Import the File class
import java.io.FileNotFoundException;  // Import this class to handle errors
import java.util.Scanner; // Import the Scanner class to read text files


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

    private static String astraeaSpans = "/local/astraea-spans.txt";

    // private boolean serverDisabled = false;

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

    static boolean astraeaSpanStatus(String spanId){
        // tsl: we need svc:operation:url
        // httpRequest.getHeaders().get("host").get(0).split(":")[0] :  httpRequest.getMethod() : httpRequest.getURI().toString()
        try {
            System.out.println(" *-* Reading " + astraeaSpans);
            File myObj = new File(astraeaSpans);
            System.out.println(" *-* read " + astraeaSpans);
            Scanner myReader = new Scanner(myObj);
            while (myReader.hasNextLine()) {
              String data = myReader.nextLine();
              if (data.equals(spanId)){
                System.out.println(" *-* Disabling!! " + spanId);
                return false;
              }
            }
            myReader.close();
        }catch (FileNotFoundException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
          }
          System.out.println(" *-* Enabling!! " + spanId); 
        return true;
    }


    @Override
    public ClientHttpResponse intercept(HttpRequest httpRequest, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse httpResponse;
        boolean serverDisabled = false;

        System.out.println("*-* Client http req" + httpRequest.getURI().toString() + " " +  httpRequest.getMethod());
        System.out.println("*-*  Headers now at the beginning of client  " + httpRequest.getHeaders());

        // toslali: get last active span (ASTRAEA may have disabled some in the middle)
        Scope serverSpan = tracer.scopeManager().active();

        // tsl: remove below later
        if (serverSpan != null) {
            System.out.println("*-*  ex span " + serverSpan.span());
        } else {
            System.out.println("*-*  server ex span is null ");
        }

        if (serverSpan.span().getBaggageItem("astraea") != null){
            System.out.println("*-*  Cokemelli " + serverSpan.span().getBaggageItem("astraea"));

            String contextInBag = serverSpan.span().getBaggageItem("astraea");            
            final HashMap<String, String> headers = new HashMap<String, String>();
            headers.put("uber-trace-id", contextInBag);
            parentSpanContext = tracer.extract(Format.Builtin.HTTP_HEADERS,
                            new TextMapExtractAdapter(headers));

            System.out.println("*-*  Cokemelli2 " + parentSpanContext);

            serverSpan.close();
            serverDisabled = true;
        }

        // Span state ==> <svc:opName:url> 
        // httpRequest.getHeaders().get("host").get(0).split(":")[0] :  httpRequest.getMethod() : httpRequest.getURI().toString()
        String svc = httpRequest.getHeaders().get("host").get(0).split(":")[0];
        System.out.println("*-*  SVC: " +  svc);
        String op = httpRequest.getMethod().toString();
        System.out.println("*-*  OPNAME: " + op );

        // str.lastIndexOf(separator);
        String url = httpRequest.getURI().toString();
        String astraeaUrl = url.substring(0, url.lastIndexOf("/"));
        System.out.println("*-*  URL : " + astraeaUrl);
        
        if (!astraeaSpanStatus(svc + ":" + op + ":" + astraeaUrl)) { // if client span disabled by ASTRAEA ; toslali: start the span but inject parent context!!!
            System.out.println("*-*  Dsiabled by ASTRAEA");

            if (serverSpan != null) {
                System.out.println("*-*  server span is here so  injecting");
                // tsl: that  works for disabling client span -- paassing the server span context
                tracer.inject(serverSpan.span().context(), Format.Builtin.HTTP_HEADERS,
                        new HttpHeadersCarrier(httpRequest.getHeaders()));
            } else {
                System.out.println("*-*  server spn is null so injecting REQUESTS INCOMING SPAN ");
                tracer.inject(parentSpanContext, Format.Builtin.HTTP_HEADERS,
                        new HttpHeadersCarrier(httpRequest.getHeaders()));
            }

            try {
                httpResponse = execution.execute(httpRequest, body);
            } catch (Exception ex) {

                throw ex;
            }
          
        } else {
            System.out.println("*-*  Client Enabled by ASTRAEA");
            SpanContext parentSpan;

            if (serverDisabled) { // if server span is disabled get the span context from baggage i.e., astraea -> parentSpanContext
                System.out.println("*-*  Server span is disablled so getting span context from bagg");

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
