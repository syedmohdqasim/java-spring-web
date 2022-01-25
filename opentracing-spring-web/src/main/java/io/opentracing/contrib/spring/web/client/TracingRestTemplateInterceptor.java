package io.opentracing.contrib.spring.web.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import java.util.Map;

import io.opentracing.contrib.spring.web.interceptor.HttpServletRequestExtractAdapter;

import java.io.File; // Import the File class
import java.io.FileNotFoundException; // Import this class to handle errors
import java.io.FileReader;
import java.util.Scanner; // Import the Scanner class to read text files

import java.util.Timer;
import java.util.TimerTask;
import java.util.HashSet;

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

    private static String astraeaSpans = "/astraea-spans";
    private static String serviceName = "";

    private HashSet<String> astraeaSpansSet = new HashSet<>(); 
    private final Object lock = new Object();

    // private boolean serverDisabled = false;

    public TracingRestTemplateInterceptor() {
        // String tracerService = tracer.toString();
        // this.serviceName = tracerService.substring(tracerService.indexOf("serviceName=") + 12 , tracerService.indexOf(", reporter="));
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

        Timer timer = new Timer();
        // Create a background thread that periodically populates astraea span states from a file
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                // System.out.println("*-* Running rest: " + new java.util.Date());
                HashSet<String> astraeaSpansSetLocal = new HashSet<>(); 
                try(BufferedReader br = new BufferedReader(new FileReader(astraeaSpans))) {
                        String line = br.readLine();
                        while (line != null) {
                            astraeaSpansSetLocal.add(line);
                            line = br.readLine();
                        }
                }catch(Exception e){
                    System.out.println("!! An error occurred in timer task for rest. " + e.getMessage());
                }
                synchronized (lock) {
                    astraeaSpansSet = astraeaSpansSetLocal;
                }
                // System.out.println("*-* Populated rest: " + astraeaSpansSet);
            }
        }, 0, 10000);

    }

    boolean astraeaSpanStatus(String spanId){
        // tsl: we need svc:operation:url
        // httpRequest.getHeaders().get("host").get(0).split(":")[0] :  httpRequest.getMethod() : httpRequest.getURI().toString()
        boolean result = true;
        synchronized (lock) {
            if (astraeaSpansSet.contains(spanId)){
                result = false;

            }
        }
        return result;        
    }


    // VAIF-like implementation - just for overhead measurements
    private boolean astraeaSpanStatusFS(String spanId){
        try(BufferedReader br = new BufferedReader(new FileReader(astraeaSpans))) {
            String line = br.readLine();
            while (line != null) {
                // System.out.println(" *-* Line " + line);                
                if (line.equals(spanId)){
                    // System.out.println(" *-* Disabling!! " + spanId);
                    return false;
                } 
                line = br.readLine();
            }
        }catch(Exception e){
            System.out.println("!! An error occurred. " + e.getMessage());
        }
        return true;
    }



    // tsl: check for uppercase and int in the url - if so crop it
    static boolean urlLastPartCrop(String urlLastPart){
    
        for(int i=0;i < urlLastPart.length();i++) {
            char ch = urlLastPart.charAt(i);
            
            if( Character.isDigit(ch)) {
                return true;
            }
            else if (Character.isUpperCase(ch)) {
                return true;
            }
        }
        return false;

    }

    // tsl: no uppercase, no integer and special condition for order!!
    static String astraeaURLFormat(String originalUrl){
        String astraeaURL = originalUrl;
        // get the last part 
        String urlLastPart = astraeaURL.substring(astraeaURL.lastIndexOf("/") + 1);

        while (urlLastPartCrop(urlLastPart)){
            astraeaURL = astraeaURL.substring(0,astraeaURL.lastIndexOf("/"));
            urlLastPart = astraeaURL.substring(astraeaURL.lastIndexOf("/") + 1);
        }

        // System.out.println("*-*  astraea URL : " + astraeaURL);

        return astraeaURL;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest httpRequest, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse httpResponse;
        boolean serverDisabled = false;

        // System.out.println("*-* tracer for svc name "  + tracer);
        String tracerService = tracer.toString();
        String serviceName = tracerService.substring(tracerService.indexOf("serviceName=") + 12 , tracerService.indexOf(", reporter="));
        
        // System.out.println("*-* tracer for svc name "  + serviceName);
        // System.out.println("*-* Client http req" + httpRequest.getURI().toString() + " " +  httpRequest.getMethod());
        // System.out.println("*-*  Headers now at the beginning of client  " + httpRequest.getHeaders());

        // now client span has a parent server span -  below we get parentspan of server span (i.e., grandparent)
        MultiValueMap<String, String> rawHeaders = httpRequest.getHeaders();
        final HashMap<String, String> headersClient = new HashMap<String, String>();
        for (String key : rawHeaders.keySet()) {
            headersClient.put(key, rawHeaders.get(key).get(0));
        }
        SpanContext parentSpanContext = tracer.extract(Format.Builtin.HTTP_HEADERS,
                            new TextMapExtractAdapter(headersClient));

        // System.out.println("*-* grand parent here: " + parentSpanContext  );

        // toslali: get server span here - if null then it is disabled by astraea already
        Scope serverSpan = tracer.scopeManager().active();

        // tsl: remove below later
        if (serverSpan != null) {
            // System.out.println("*-*  ex span " + serverSpan.span());
        } else {
            // System.out.println("*-*  server ex span is null ");
            serverDisabled = true;
        }

        // Span state ==> <svc:opName:url> 
        // httpRequest.getHeaders().get("host").get(0).split(":")[0] :  httpRequest.getMethod() : httpRequest.getURI().toString()

        String op = httpRequest.getMethod().toString();
        // System.out.println("*-*  OPNAME: " + op );

        // str.lastIndexOf(separator);
        String url = httpRequest.getURI().toString();
        // System.out.println("*-*  URL : " + url);
        
        if (!astraeaSpanStatusFS(serviceName + ":" + op + ":" + astraeaURLFormat(url))) { // if client span disabled by ASTRAEA ; toslali: start the span but inject parent context!!!
            // System.out.println("*-*  Dsiabled by ASTRAEA");

            // if (serverSpan != null) {
            if (!serverDisabled){
                // System.out.println("*-*  server span is here so  injecting");
                // tsl: that  works for disabling client span -- paassing the server span context
                tracer.inject(serverSpan.span().context(), Format.Builtin.HTTP_HEADERS,
                        new HttpHeadersCarrier(httpRequest.getHeaders()));
            } else {
                // System.out.println("*-*  server spn is null so injecting REQUESTS INCOMING SPAN ");
                tracer.inject(parentSpanContext, Format.Builtin.HTTP_HEADERS,
                        new HttpHeadersCarrier(httpRequest.getHeaders()));
            }

            try {
                httpResponse = execution.execute(httpRequest, body);
            } catch (Exception ex) {

                throw ex;
            }
          
        } else {
            // System.out.println("*-*  Client Enabled by ASTRAEA");
            // SpanContext parentSpan;

            if (serverDisabled) { // if server span is disabled get the span context from baggage i.e., astraea -> parentSpanContext
                // System.out.println("*-*  Server span is disablled so getting span context from bagg");

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
