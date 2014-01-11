package com.baasbox.android;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.baasbox.android.exceptions.BAASBoxException;
import com.baasbox.android.exceptions.BAASBoxInvalidSessionException;
import com.baasbox.android.impl.Logging;
import com.baasbox.android.spi.AsyncRequestDispatcher;
import com.baasbox.android.spi.CredentialStore;
import com.baasbox.android.spi.RestClient;

import org.apache.http.HttpResponse;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by eto on 23/12/13.
 */
final class DefaultDispatcher implements AsyncRequestDispatcher {
    private final static AtomicInteger REQUEST_COUNTER=new AtomicInteger(Integer.MIN_VALUE);
    private final RestClient client;
    private final BAASBox box;


    private final BAASBox.Config config;
    private final ResponseHandler dispatcher;
    private final Worker[] workers;
    private final CredentialStore credentialStore;

    private final PriorityBlockingQueue<BaasRequest<?, ?>> requests;
    final ConcurrentHashMap<Integer, BaasRequest<?, ?>> submittedRequests;
    final ConcurrentHashMap<Integer, BAASBox.BAASHandler<?, ?>> handlersMap;
    final ConcurrentHashMap<Integer, Object> tagsMap;

    DefaultDispatcher(BAASBox box, RestClient client) {
        this.box = box;
        this.client = client;
        this.config = box.config;
        this.credentialStore = box.credentialStore;
        this.requests = new PriorityBlockingQueue<BaasRequest<?,?>>();
        this.dispatcher = new ResponseHandler(this);
        this.workers = createWorkers(config.NUM_THREADS);

        this.submittedRequests = new ConcurrentHashMap<Integer, BaasRequest<?, ?>>();
        this.handlersMap = new ConcurrentHashMap<Integer, BAASBox.BAASHandler<?, ?>>();
        this.tagsMap = new ConcurrentHashMap<Integer, Object>();
    }

    private static Worker[] createWorkers(int threads) {
        Worker[] workers = new Worker[threads == 0 ? Runtime.getRuntime().availableProcessors() : threads];
        return workers;
    }

    @Override
    public void start() {
        stop();
        for(int i=0;i<workers.length;i++){
            workers[i]=new Worker(this);
            workers[i].start();
        }
    }

    @Override
    public void stop() {
        for(int i = 0;i<workers.length;i++){
            if(workers[i]!=null){
                workers[i].quit();
                workers[i]=null;
            }
        }
    }


    @Override
    public void cancel(RequestToken tag) {
        BaasRequest<?, ?> req = submittedRequests.get(tag.requestId);
        if (req != null && req.cancel()) {
            submittedRequests.remove(tag.requestId);
            handlersMap.remove(tag.requestId);
            tagsMap.remove(tag.requestId);
        }
    }


    @Override
    public RequestToken post(BaasRequest<?, ?> request) {
        request.requestNumber = REQUEST_COUNTER.getAndIncrement();
        BAASBox.BAASHandler<?, ?> handler = request.handler;
        request.handler = null;
        Object tag = request.tag;
        request.tag = null;
        RequestToken token = new RequestToken(request.requestNumber);

        submittedRequests.put(request.requestNumber, request);
        handlersMap.put(request.requestNumber, handler);
        if (tag != null) {
            tagsMap.put(request.requestNumber, tag);
        }
        requests.add(request);
        return token;
    }

    @Override
    public <T> void resume(RequestToken token, T tag, BAASBox.BAASHandler<?, T> handler) {
        BaasRequest<?, ?> req = submittedRequests.get(token.requestId);
        Logging.debug("Resuming: " + (req != null) + (req != null ? (req.status() + " " + req.suspended.get()) : "---"));
        if (req != null && req.suspended.compareAndSet(true, false)) {

            if (tag != null) tagsMap.put(req.requestNumber, tag);
            handlersMap.put(req.requestNumber, handler);
            if (req.status.get() == BaasRequest.State.EXECUTED) {
                dispatcher.post(req);
            }
        }

    }


    private <R, T> void finishDispatch(BaasRequest<R, T> req) {
        Logging.debug("Dispatching: " + (req != null) + (req.suspended.get()) + "" + (req.status()));
        if (req.advanceIfNotCanceled(BaasRequest.State.EXECUTED, BaasRequest.State.DELIVERED) && !req.suspended.get()) {
            BAASBox.BAASHandler<R, T> h = (BAASBox.BAASHandler<R, T>) handlersMap.remove(req.requestNumber);
            T t = (T) tagsMap.remove(req.requestNumber);
            h.handle(req.result, t);
            submittedRequests.remove(req.requestNumber);
        }
    }

    @Override
    public void suspend(RequestToken token) {
        BaasRequest<?, ?> req = submittedRequests.get(token.requestId);
        Logging.debug("request status: " + (req != null) + " " + (req != null ? req.status.get() : "---"));
        if (req != null && req.status.get() != BaasRequest.State.DELIVERED && req.suspended.compareAndSet(false, true)) {
            handlersMap.remove(token.requestId);
            tagsMap.remove(token.requestId);
        }
    }

    private static class ResponseHandler extends Handler{
        private final DefaultDispatcher dispatcher;

        ResponseHandler(DefaultDispatcher dispatcher) {
            super(Looper.getMainLooper());
            this.dispatcher = dispatcher;
        }

        @Override
        public void handleMessage(Message msg) {
            BaasRequest req =(BaasRequest)msg.obj;
            dispatcher.finishDispatch(req);
        }


        public void post(BaasRequest request){
            sendMessage(obtainMessage(request.requestNumber,request));
        }
    }

    private static class Worker extends Thread{
        private final PriorityBlockingQueue<BaasRequest<?,?>> requests;
        private final ResponseHandler poster;
        private final RestClient client;
        private final BAASBox.Config config;
        private final CredentialStore credentialStore;
        private final DefaultDispatcher dispatcher;
        private volatile boolean quit;

        Worker(DefaultDispatcher dispatcher){
            this.dispatcher=dispatcher;
            this.requests=dispatcher.requests;
            this.client=dispatcher.client;
            this.poster=dispatcher.dispatcher;
            this.config = dispatcher.config;
            this.credentialStore=dispatcher.credentialStore;
            this.quit = false;
        }

        void quit(){
            quit=true;
            interrupt();
        }

        @Override
        public void run() {
            BaasRequest<?,?> request;
            while (true) {
                try {
                    request = requests.take();
                } catch (InterruptedException interrupt) {
                    if (quit) {
                        return;
                    }
                    continue;
                }

                request.boundedThread = Thread.currentThread();
                boolean executed = false;
                try {
                    executed = request.advanceIfNotCanceled(BaasRequest.State.ACTIVE, BaasRequest.State.PROCESSING) &&
                            executeRequest(request, client) &&
                            request.advanceIfNotCanceled(BaasRequest.State.PROCESSING, BaasRequest.State.EXECUTED);
                    request.boundedThread = null;
                } catch (InterruptedException e) {
                    if (request.isCanceled()) {
                        continue;
                    }
                }

                if (executed) poster.post(request);

            }
        }


        private <T> boolean executeRequest(final BaasRequest<T, ?> req, RestClient client) throws InterruptedException {
            boolean handle = true;
            try {
                Logging.debug("REQUEST: " + req.httpRequest);
                HttpResponse response = client.execute(req.httpRequest);
                T t = req.parser.parseResponse(req, response, config, credentialStore);
                req.result = BaasResult.success(t);
            } catch (BAASBoxInvalidSessionException ex){
                Logging.debug("invalid session");
                if(req.takeRetry()){
                    Logging.debug("retry");
                    LoginRequest<Void> refresh = new LoginRequest<Void>(dispatcher.box, MAX_PRIORITY, null, new BAASBox.BAASHandler<Void, Void>() {
                        @Override
                        public void handle(BaasResult<Void> result, Void tag) {
                            dispatcher.post(req);
                        }
                    });
                    dispatcher.post(refresh);
                    handle = false;
                } else {
                    req.result = BaasResult.failure(ex);
                }
            }catch (BAASBoxException e) {
                Logging.debug("error with " + e.getMessage());
                req.result= BaasResult.failure(e);
            }
            return handle;
        }


    }

}
