import okhttp3.ResponseBody;

import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

abstract class HttpServiceMethod<ResponseT, ReturnT> extends ServiceMethod<ReturnT> {

    static <ResponseT, ReturnT> HttpServiceMethod<ResponseT, ReturnT> parseAnnotations(Retrofit retrofit, Method method, RequestFactory requestFactory) {
        Annotation[] annotations = method.getAnnotations();

        Type returnType = method.getGenericReturnType();

        if (!(returnType instanceof ParameterizedType)) {

        }

        CallAdapter<ResponseT, ReturnT> callAdapter = createCallAdapter(retrofit, method, returnType, annotations);
        Type responseType = callAdapter.responseType();
        if (responseType == okhttp3.Response.class) {
            throw Utils.methodError(method, "'"
                    + Utils.getRawType(responseType).getName()
                    + "' is not a valid response body type. Did you mean ResponseBody?");
        }

        if (responseType == Response.class) {
            throw Utils.methodError(method, "Response must include generic type (e.g., Response<String>)");
        }

        Converter<ResponseBody, ResponseT> responseConverter =
                createResponseConverter(retrofit, method, responseType);

        okhttp3.Call.Factory callFactory = retrofit.callFactory;
        return new CallAdapted<>(requestFactory, callFactory, responseConverter, callAdapter);

    }

    private static <ResponseT, ReturnT> CallAdapter<ResponseT, ReturnT> createCallAdapter(
            Retrofit retrofit, Method method, Type returnType, Annotation[] annotations) {
        try {
            //noinspection unchecked
            return (CallAdapter<ResponseT, ReturnT>) retrofit.callAdapter(returnType, annotations);
        } catch (RuntimeException e) { // Wide exception range because factories are user code.
            throw Utils.methodError(method, e, "Unable to create call adapter for %s", returnType);
        }
    }

    private static <ResponseT> Converter<ResponseBody, ResponseT> createResponseConverter(
            Retrofit retrofit, Method method, Type responseType) {
        Annotation[] annotations = method.getAnnotations();
        try {
            return retrofit.responseBodyConverter(responseType, annotations);
        } catch (RuntimeException e) { // Wide exception range because factories are user code.
            throw Utils.methodError(method, e, "Unable to create converter for %s", responseType);
        }
    }

    private final RequestFactory requestFactory;
    private final okhttp3.Call.Factory callFactory;
    private final Converter<ResponseBody, ResponseT> responseConverter;

    HttpServiceMethod(RequestFactory requestFactory,
                      okhttp3.Call.Factory callFactory,
                      Converter<ResponseBody, ResponseT> responseConverter) {
        this.requestFactory = requestFactory;
        this.callFactory = callFactory;
        this.responseConverter = responseConverter;
    }

    @Nullable
    @Override
    ReturnT invoke(Object[] args) {
        Call<ResponseT> call = new OkHttpCall<>(requestFactory, args, callFactory, responseConverter);
        return adapt(call, args);
    }

    protected abstract @Nullable ReturnT adapt(Call<ResponseT> call, Object[] args);

    static final class CallAdapted<ResponseT, ReturnT> extends HttpServiceMethod<ResponseT, ReturnT> {
        private final CallAdapter<ResponseT, ReturnT> callAdapter;

        CallAdapted(RequestFactory requestFactory, okhttp3.Call.Factory callFactory,
                    Converter<ResponseBody, ResponseT> responseConverter,
                    CallAdapter<ResponseT, ReturnT> callAdapter) {
            super(requestFactory, callFactory, responseConverter);
            this.callAdapter = callAdapter;
        }

        @Override protected ReturnT adapt(Call<ResponseT> call, Object[] args) {
            return callAdapter.adapt(call);
        }
    }
}
