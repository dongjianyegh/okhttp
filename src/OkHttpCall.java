import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.*;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;

public class OkHttpCall<T> implements Call<T> {

    private final okhttp3.Call.Factory callFactory;
    private final Object[] args;
    private final RequestFactory requestFactory;
    private final Converter<ResponseBody, T> responseConverter;

    private okhttp3.Response rawResponse;
    private okhttp3.Call rawCall;

    @GuardedBy("this")
    private boolean executed;

    private volatile boolean cancel;

    public OkHttpCall(RequestFactory requestFactory, Object[] args,
                      okhttp3.Call.Factory callFactory,
                      Converter<ResponseBody, T> responseConverter) {
        this.requestFactory = requestFactory;
        this.args = args;
        this.callFactory = callFactory;
        this.responseConverter = responseConverter;
    }

    private okhttp3.Call createRawCall() throws IOException {
        return callFactory.newCall(requestFactory.create(args));
    }

    @Override
    public Response<T> execute() throws IOException {
        okhttp3.Call call;

        synchronized (OkHttpCall.this) {
            if (executed) {
                throw new IllegalArgumentException("already executed!");
            }
            executed = true;

            call = rawCall;
            if (rawCall == null) {
                try {
                    call = rawCall = createRawCall();
                } catch (IOException | RuntimeException | Error e) {
                    throw e;
                }
            }
        }


        return parseResponse(call.execute());
    }

    private Response<T> parseResponse(okhttp3.Response rawResponse) throws IOException {
        ResponseBody responseBody = rawResponse.body();

        // Remove the body's source (the only stateful object) so we can pass the response along.
        rawResponse = rawResponse.newBuilder()
                .body(new NoContentResponseBody(responseBody.contentType(), responseBody.contentLength()))
                .build();


        int code = rawResponse.code();

        if (code < 200 || code >= 300) {
            try {
                return Response.error(Utils.buffer(responseBody), rawResponse);
            } finally {
                responseBody.close();
            }
        }

        if (code == 204 || code == 205) {
            responseBody.close();
            return Response.success(null, rawResponse);
        }

        try {
            ExceptionCathResponseBody exceptionCathResponseBody = new ExceptionCathResponseBody(responseBody);
            final T body = responseConverter.convert(exceptionCathResponseBody);
            return Response.success(body, rawResponse);

        } catch (RuntimeException e) {
            throw e;
        }
    }

    @Override
    public void enqueue(Callback<T> callback) {
        okhttp3.Call call;
        synchronized (OkHttpCall.this) {
            if (executed) {
                throw new IllegalArgumentException("already executed!");
            }
            executed = true;

            call = rawCall;
            if (rawCall == null) {
                try {
                    call = rawCall = createRawCall();
                } catch (IOException | RuntimeException | Error e) {

                }
            }
        }

        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                callFailure(e);
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response rawResponse) throws IOException {
                Response<T> response;
                try {
                    response = parseResponse(rawResponse);
                } catch (Throwable e) {
                    callFailure(e);
                    return;
                }

                try {
                    callback.onResponse(OkHttpCall.this, response);
                } catch (Throwable t) {
                    throwIfFatal(t);
                    t.printStackTrace(); // TODO this is not great
                }
            }

            private void callFailure(Throwable e) {
                try {
                    callback.onFailure(OkHttpCall.this, e);
                } catch (Throwable t) {
                    throwIfFatal(t);
                    t.printStackTrace(); // TODO this is not great
                }
            }
        });
    }

    @Override
    public synchronized boolean isExecuted() {
        return executed;
    }

    @Override
    public void cancel() {
        if (!cancel) {
            cancel = true;
        }

        okhttp3.Call call;
        synchronized (this) {
            call = rawCall;
        }
        if (call != null) {
            call.cancel();
        }
    }

    @Override
    public boolean isCanceled() {
        if (cancel) {
            return true;
        }
        synchronized (this) {
            return rawCall != null && rawCall.isCanceled();
        }
    }

    @Override
    public Call<T> clone() {
        return null;
    }

    @Override
    public Request request() {
        return null;
    }

    static final class NoContentResponseBody extends ResponseBody {
        private final @Nullable MediaType contentType;
        private final long contentLength;

        NoContentResponseBody(@Nullable MediaType contentType, long contentLength) {
            this.contentType = contentType;
            this.contentLength = contentLength;
        }

        @Override public MediaType contentType() {
            return contentType;
        }

        @Override public long contentLength() {
            return contentLength;
        }

        @Override public BufferedSource source() {
            throw new IllegalStateException("Cannot read raw response body of a converted body.");
        }
    }

    static final class ExceptionCathResponseBody extends ResponseBody {
        private final ResponseBody rawResponseBody;
        @Nullable IOException thrownException;

        public ExceptionCathResponseBody(ResponseBody responseBody) {
            super();
            rawResponseBody = responseBody;
        }

        @Override
        public void close() {
            rawResponseBody.close();
        }

        @Nullable
        @Override
        public MediaType contentType() {
            return rawResponseBody.contentType();
        }

        @Override
        public long contentLength() {
            return rawResponseBody.contentLength();
        }

        @Override
        public BufferedSource source() {
            final BufferedSource rawSource = rawResponseBody.source();
            return Okio.buffer(new ForwardingSource(rawSource) {
                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                    try {
                        return super.read(sink, byteCount);
                    } catch (IOException e) {
                        thrownException = e;
                        throw e;
                    }
                }

                @Override
                public Timeout timeout() {
                    return super.timeout();
                }

                @Override
                public void close() throws IOException {
                    super.close();
                }

                @Override
                public String toString() {
                    return super.toString();
                }
            });
        }
    }

    // https://github.com/ReactiveX/RxJava/blob/6a44e5d0543a48f1c378dc833a155f3f71333bc2/
    // src/main/java/io/reactivex/exceptions/Exceptions.java#L66
    static void throwIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        } else if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        } else if (t instanceof LinkageError) {
            throw (LinkageError) t;
        }
    }
}
