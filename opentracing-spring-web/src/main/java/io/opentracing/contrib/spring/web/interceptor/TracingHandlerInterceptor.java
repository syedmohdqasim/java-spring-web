package io.opentracing.contrib.spring.web.interceptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;

import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import io.opentracing.References;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.contrib.spring.web.client.HttpHeadersCarrier;
import io.opentracing.contrib.web.servlet.filter.TracingFilter;



import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.function.Function;


import java.io.BufferedReader;
import java.io.FileReader;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import java.io.File;  // Import the File class
import java.io.FileNotFoundException;  // Import this class to handle errors
import java.util.Scanner; // Import the Scanner class to read text files

import java.util.Timer;
import java.util.TimerTask;

import java.util.HashSet;
import java.lang.Thread;
import java.util.Random;

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

    //tsl: change active span
    public static final String SERVER_SPAN_CONTEXT = TracingFilter.class.getName() + ".activeSpanContext";



    private Tracer tracer;
    private List<HandlerInterceptorSpanDecorator> decorators;

    private static String astraeaSpans = "/astraea-spans/states";

    // private String message = null;
    private HashSet<String> astraeaSpansSet = new HashSet<>(); 
    private final Object lock = new Object();

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

        Timer timer = new Timer();
        // Create a background thread that periodically populates astraea span states from a file
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                // System.out.println("*-* Running: " + new java.util.Date());
                HashSet<String> astraeaSpansSetLocal = new HashSet<>(); 
                try(BufferedReader br = new BufferedReader(new FileReader(astraeaSpans))) {
                        String line = br.readLine();
                        while (line != null) {
                            astraeaSpansSetLocal.add(line);
                            line = br.readLine();
                        }
                }catch(Exception e){
                    System.out.println("!! An error occurred in timer task. " + e.getMessage());
                }
                synchronized (lock) {
                    astraeaSpansSet = astraeaSpansSetLocal;
                }
                // astraeaSpansSet = astraeaSpansSetLocal;
                // System.out.println("*-* Populated server: " + astraeaSpansSet);
            }
        }, 0, 10000);
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

    // tsl: [ignore now] special condition for leaf spans = endswith ":1" isLeafFlag (no need leafflag now)
    // so 0 for enabled, 1 for disabled, 2 for disabled leaf yapilabilir
    private boolean astraeaSpanStatus(String spanId){
        // tsl: we need svc:operation ---> for server spans
        // httpServletRequest.getHeader("host").get(0).split(":")[0] : opName
       
        boolean result = true;
        synchronized (lock) {
        if (astraeaSpansSet.contains(spanId)){
                    result = false;
        }
        }
        // System.out.println(" *-* Enabling decision for  server span!! " + spanId + " == " + result); 
        return result;       
    }



    private void astraeaDelayInjected(String spanId){
        // System.out.println(" *-* checking delay now client " + spanId + "\n"+ astraeaSpansSet);
        boolean isDelayed = false;

        synchronized (lock) {
            if (astraeaSpansSet.contains("inject-" + spanId)){
                isDelayed = true;
                
            }else{
                // System.out.println(" *-* Nodelay enabled for span!! " + spanId );
            }
        }
        if (isDelayed){
            // sleep here
            isDelayed = false;
            System.out.println(" *-* Sleep enabled for span!! " + spanId );

            int std = 3;
            int delay = 5; // milisecond

            Random randomno = new Random();
            double sample = randomno.nextGaussian()*std+delay; // change 15=std and 60 = mean
            // System.out.println("*-* Gaussian triggered for span "+ spanId + " with " + String.valueOf(sample));

            int newdelay = (int)sample;
            
            if(newdelay > 0){
                try{
                    Thread.sleep(newdelay);
                    // System.out.println("*-* Uyandim");
                }
                catch(InterruptedException e){
                    // System.out.println("*-* Thread uyuma problemi!");
                }
            }
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object handler)
            throws Exception {

        if (!isTraced(httpServletRequest)) {
            System.out.println("*-* Not traced");
            return true;
        }

         //tsl: now the active should be parent so that we can set the span context properly according to enable/disable
        Scope serverSpan = tracer.scopeManager().active();
        
        // System.out.println("*-* gelmistik tracing handler");
        // if (serverSpan != null){
        //     System.out.println("*-* Server information at handler: " +  serverSpan.span());
            
        // }

        String tracerService = tracer.toString();
        String serviceName = tracerService.substring(tracerService.indexOf("serviceName=") + 12 , tracerService.indexOf(", reporter="));
        // System.out.println("*-* tracer for svc name at server "  + serviceName);

        SpanContext extractedContext = tracer.extract(Format.Builtin.HTTP_HEADERS,
            new HttpServletRequestExtractAdapter(httpServletRequest));
        // System.out.println("*-* Extracted context from parent " + extractedContext);

        String opName =  handler instanceof HandlerMethod ?
                        ((HandlerMethod) handler).getMethod().getName() : null;

        // tsl: delete below later
        // HttpHeaders httpHeaders = Collections.list(httpServletRequest.getHeaderNames())
        // .stream()
        // .collect(Collectors.toMap(
        //     Function.identity(),
        //     h -> Collections.list(httpServletRequest.getHeaders(h)),
        //     (oldValue, newValue) -> newValue,
        //     HttpHeaders::new
        // ));
        // System.out.println("*-* Extracted headers at the beginning " + httpHeaders);

        // tsl: aSTRAEA baggage item to pass parent context into client if serverspan is disabled
        // httpServletRequest.getHeader("host").get(0).split(":")[0] : opName
        String svc = httpServletRequest.getHeader("host").split(":")[0];
        // System.out.println("*-*  SVC: " +  serviceName);
        // System.out.println("*-*  OPNAME: " + opName );

        // long startTime = System.nanoTime();
        boolean astraeaSpanStatus = astraeaSpanStatus(serviceName + ":" + opName);
        // long endTime = System.nanoTime();

        // System.out.println("*-* Astraea overhead: " + (endTime - startTime));


        //tsl: inject delay
        astraeaDelayInjected(serviceName + ":" + opName);



        if ( !astraeaSpanStatus){ 
            // opName.equalsIgnoreCase("getRouteByTripId2")
            // System.out.println("*-* Do not create soan for this");
            serverSpan.close();          
        }
        else{ // we do nothing as server span is enabled
        //     System.out.println("*-* Creating server span now");
            for (HandlerInterceptorSpanDecorator decorator : decorators) {
                decorator.onPreHandle(httpServletRequest, handler, serverSpan.span());
            }
        }
            
        // tsl: async requests are ignored for now
        // else{

        //     if (serverSpan == null) {
        //         System.out.println("*-* Null olmustu ");
        //         if (httpServletRequest.getAttribute(CONTINUATION_FROM_ASYNC_STARTED) != null) {
        //             Span contd = (Span) httpServletRequest.getAttribute(CONTINUATION_FROM_ASYNC_STARTED);
        //             serverSpan = tracer.scopeManager().activate(contd, false);
        //             httpServletRequest.removeAttribute(CONTINUATION_FROM_ASYNC_STARTED);
        //         } else {
        //             // spring boot default error handling, executes interceptor after processing in the filter (ugly huh?)
        //     System.out.println("*-* Prehandle Client http req" + httpServletRequest.getRequestURI().toString() + httpServletRequest.getMethod());
    
        //             serverSpan = tracer.buildSpan(httpServletRequest.getMethod())
        //                     .addReference(References.FOLLOWS_FROM, TracingFilter.serverSpanContext(httpServletRequest))
        //                     .startActive(true);
    
        //             // if (opName.equalsIgnoreCase("getRouteByTripId")){
        //             //     System.out.println("*-* Do not create soan for this");
        //             // }
        //             // else{
                        
        //             //     System.out.println("*-* Creating the new span now");
        //             //     SpanContext extractedContext = tracer.extract(Format.Builtin.HTTP_HEADERS,
        //             //     new HttpServletRequestExtractAdapter(httpServletRequest));
    
        //             //     serverSpan = tracer.buildSpan(opName)
        //             //     .asChildOf(extractedContext)
        //             //     // .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
        //             //     .startActive(true);
    
        //             // }
    
        //         }
        //         Deque<Scope> activeSpanStack = getScopeStack(httpServletRequest);
        //         activeSpanStack.push(serverSpan);
        //     }
    
        //     for (HandlerInterceptorSpanDecorator decorator : decorators) {
        //         decorator.onPreHandle(httpServletRequest, handler, serverSpan.span());
        //     }
        // }

        

        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted (
            HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object handler)
            throws Exception {

                // System.out.println("*-* 111");

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
        // System.out.println("*-* 222");
    }

    @Override
    public void afterCompletion(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
                                Object handler, Exception ex) throws Exception {

        // System.out.println("*-* 333");
        if (!isTraced(httpServletRequest)) {
            return;
        }

        Deque<Scope> scopeStack = getScopeStack(httpServletRequest);
        if(scopeStack.size() > 0) {
            Scope scope = scopeStack.pop();
            // System.out.println("*-* after completion for scope " + scope != null ? "null": scope.span());
            onAfterCompletion(httpServletRequest, httpServletResponse, handler, ex, scope.span());
            scope.close();
        } else if (tracer.activeSpan() != null){
                onAfterCompletion(httpServletRequest, httpServletResponse, handler, ex, tracer.activeSpan());
            
        }
        // otherwise astraea disabled so do nothing!      
    }

    private void onAfterCompletion(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
                                   Object handler, Exception ex, Span span) {
                                    // System.out.println("*-* 444");
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
