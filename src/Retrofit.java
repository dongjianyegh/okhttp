import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static java.util.Collections.unmodifiableList;

public class Retrofit {
    private final Map<Method, ServiceMethod<?>> serviceMethodCache = new ConcurrentHashMap<>();

    final okhttp3.Call.Factory callFactory;
    final List<Converter.Factory> converterFactories;
    final List<CallAdapter.Factory> callAdapterFactories;
    final HttpUrl baseUrl;
    final @Nullable Executor callbackExecutor;
    final boolean validateEagerly;

    Retrofit(okhttp3.Call.Factory callFactory, HttpUrl baseUrl,
             List<Converter.Factory> converterFactories, List<CallAdapter.Factory> callAdapterFactories,
             @Nullable Executor callbackExecutor, boolean validateEagerly) {
        this.callFactory = callFactory;
        this.baseUrl = baseUrl;
        this.converterFactories = converterFactories; // Copy+unmodifiable at call site.
        this.callAdapterFactories = callAdapterFactories; // Copy+unmodifiable at call site.
        this.callbackExecutor = callbackExecutor;
        this.validateEagerly = validateEagerly;
    }

    public <T> T create(final Class<T> service) {
        validateServiceInterface(service);
        return (T) Proxy.newProxyInstance(service.getClassLoader(), new Class<?>[]{service},
                new InvocationHandler() {
                    private final Platform platform = Platform.get();
                    private final Object[] emptyArgs = new Object[0];

                    @Override
                    public @Nullable
                    Object invoke(Object proxy, Method method,
                                  @Nullable Object[] args) throws Throwable {
                        // If the method is a method from Object then defer to normal invocation.
                        if (method.getDeclaringClass() == Object.class) {
                            return method.invoke(this, args);
                        }
                        if (platform.isDefaultMethod(method)) {
                            return platform.invokeDefaultMethod(method, service, proxy, args);
                        }
                        return loadServiceMethod(method).invoke(args != null ? args : emptyArgs);
                    }
                });
    }

    ServiceMethod<?> loadServiceMethod(Method method) {
        ServiceMethod<?> result = serviceMethodCache.get(method);
        if (result != null) return result;

        synchronized (serviceMethodCache) {
            result = serviceMethodCache.get(method);
            if (result == null) {
                result = ServiceMethod.parseAnnotations(this, method);
                serviceMethodCache.put(method, result);
            }
        }
        return result;
    }

    private void validateServiceInterface(Class<?> service) {
        if (!service.isInterface()) {
            throw new IllegalArgumentException("API declarations must be interfaces.");
        }

        Deque<Class<?>> check = new ArrayDeque<>(1);
        check.add(service);
        while (!check.isEmpty()) {
            Class<?> candidate = check.removeFirst();
            if (candidate.getTypeParameters().length != 0) {
                StringBuilder message = new StringBuilder("Type parameters are unsupported on ")
                        .append(candidate.getName());
                if (candidate != service) {
                    message.append(" which is an interface of ")
                            .append(service.getName());
                }
                throw new IllegalArgumentException(message.toString());
            }
            Collections.addAll(check, candidate.getInterfaces());
        }

        if (validateEagerly) {
            Platform platform = Platform.get();
            for (Method method : service.getDeclaredMethods()) {
                if (!platform.isDefaultMethod(method) && !Modifier.isStatic(method.getModifiers())) {
                    loadServiceMethod(method);
                }
            }
        }
    }

    public <T> Converter<T, String> stringConverter(Type type, Annotation[] annotations) {
        Objects.requireNonNull(type, "type == null");
        Objects.requireNonNull(annotations, "annotations == null");

        for (int i = 0, count = converterFactories.size(); i < count; i++) {
            Converter<?, String> converter =
                    converterFactories.get(i).stringConverter(type, annotations, this);
            if (converter != null) {
                //noinspection unchecked
                return (Converter<T, String>) converter;
            }
        }

        // Nothing matched. Resort to default converter which just calls toString().
        //noinspection unchecked
        return (Converter<T, String>) BuiltInConverters.ToStringConverter.INSTANCE;
    }

    /**
     * Returns a {@link Converter} for {@link ResponseBody} to {@code type} from the available
     * {@linkplain #converterFactories() factories}.
     *
     * @throws IllegalArgumentException if no converter available for {@code type}.
     */
    public <T> Converter<ResponseBody, T> responseBodyConverter(Type type, Annotation[] annotations) {
        return nextResponseBodyConverter(null, type, annotations);
    }

    public <T> Converter<T, RequestBody> requestBodyConverter(Type type,
                                                              Annotation[] parameterAnnotations, Annotation[] methodAnnotations) {
        return nextRequestBodyConverter(null, type, parameterAnnotations, methodAnnotations);
    }

    /**
     * Returns a {@link Converter} for {@link ResponseBody} to {@code type} from the available
     * {@linkplain #converterFactories() factories} except {@code skipPast}.
     *
     * @throws IllegalArgumentException if no converter available for {@code type}.
     */
    public <T> Converter<ResponseBody, T> nextResponseBodyConverter(
            @Nullable Converter.Factory skipPast, Type type, Annotation[] annotations) {
        Objects.requireNonNull(type, "type == null");
        Objects.requireNonNull(annotations, "annotations == null");

        int start = converterFactories.indexOf(skipPast) + 1;
        for (int i = start, count = converterFactories.size(); i < count; i++) {
            Converter<ResponseBody, ?> converter =
                    converterFactories.get(i).responseBodyConverter(type, annotations, this);
            if (converter != null) {
                //noinspection unchecked
                return (Converter<ResponseBody, T>) converter;
            }
        }

        StringBuilder builder = new StringBuilder("Could not locate ResponseBody converter for ")
                .append(type)
                .append(".\n");
        if (skipPast != null) {
            builder.append("  Skipped:");
            for (int i = 0; i < start; i++) {
                builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
            }
            builder.append('\n');
        }
        builder.append("  Tried:");
        for (int i = start, count = converterFactories.size(); i < count; i++) {
            builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
        }
        throw new IllegalArgumentException(builder.toString());
    }

    /**
     * Returns a {@link Converter} for {@code type} to {@link RequestBody} from the available
     * {@linkplain #converterFactories() factories} except {@code skipPast}.
     *
     * @throws IllegalArgumentException if no converter available for {@code type}.
     */
    public <T> Converter<T, RequestBody> nextRequestBodyConverter(
            @Nullable Converter.Factory skipPast, Type type, Annotation[] parameterAnnotations,
            Annotation[] methodAnnotations) {
        Objects.requireNonNull(type, "type == null");
        Objects.requireNonNull(parameterAnnotations, "parameterAnnotations == null");
        Objects.requireNonNull(methodAnnotations, "methodAnnotations == null");

        int start = converterFactories.indexOf(skipPast) + 1;
        for (int i = start, count = converterFactories.size(); i < count; i++) {
            Converter.Factory factory = converterFactories.get(i);
            Converter<?, RequestBody> converter =
                    factory.requestBodyConverter(type, parameterAnnotations, methodAnnotations, this);
            if (converter != null) {
                //noinspection unchecked
                return (Converter<T, RequestBody>) converter;
            }
        }

        StringBuilder builder = new StringBuilder("Could not locate RequestBody converter for ")
                .append(type)
                .append(".\n");
        if (skipPast != null) {
            builder.append("  Skipped:");
            for (int i = 0; i < start; i++) {
                builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
            }
            builder.append('\n');
        }
        builder.append("  Tried:");
        for (int i = start, count = converterFactories.size(); i < count; i++) {
            builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
        }
        throw new IllegalArgumentException(builder.toString());
    }

    /**
     * Returns the {@link CallAdapter} for {@code returnType} from the available {@linkplain
     * #callAdapterFactories() factories}.
     *
     * @throws IllegalArgumentException if no call adapter available for {@code type}.
     */
    public CallAdapter<?, ?> callAdapter(Type returnType, Annotation[] annotations) {
        return nextCallAdapter(null, returnType, annotations);
    }

    /**
     * Returns the {@link CallAdapter} for {@code returnType} from the available {@linkplain
     * #callAdapterFactories() factories} except {@code skipPast}.
     *
     * @throws IllegalArgumentException if no call adapter available for {@code type}.
     */
    public CallAdapter<?, ?> nextCallAdapter(@Nullable CallAdapter.Factory skipPast, Type returnType,
                                             Annotation[] annotations) {
        Objects.requireNonNull(returnType, "returnType == null");
        Objects.requireNonNull(annotations, "annotations == null");

        int start = callAdapterFactories.indexOf(skipPast) + 1;
        for (int i = start, count = callAdapterFactories.size(); i < count; i++) {
            CallAdapter<?, ?> adapter = callAdapterFactories.get(i).get(returnType, annotations, this);
            if (adapter != null) {
                return adapter;
            }
        }

        StringBuilder builder = new StringBuilder("Could not locate call adapter for ")
                .append(returnType)
                .append(".\n");
        if (skipPast != null) {
            builder.append("  Skipped:");
            for (int i = 0; i < start; i++) {
                builder.append("\n   * ").append(callAdapterFactories.get(i).getClass().getName());
            }
            builder.append('\n');
        }
        builder.append("  Tried:");
        for (int i = start, count = callAdapterFactories.size(); i < count; i++) {
            builder.append("\n   * ").append(callAdapterFactories.get(i).getClass().getName());
        }
        throw new IllegalArgumentException(builder.toString());
    }

    public Builder newBuilder() {
        return new Builder(this);
    }

    /**
     * Build a new {@link Retrofit}.
     * <p>
     * Calling {@link #baseUrl} is required before calling {@link #build()}. All other methods
     * are optional.
     */
    public static final class Builder {
        private final Platform platform;
        private @Nullable okhttp3.Call.Factory callFactory;
        private @Nullable HttpUrl baseUrl;
        private final List<Converter.Factory> converterFactories = new ArrayList<>();
        private final List<CallAdapter.Factory> callAdapterFactories = new ArrayList<>();
        private @Nullable Executor callbackExecutor;
        private boolean validateEagerly;

        Builder(Platform platform) {
            this.platform = platform;
        }

        public Builder() {
            this(Platform.get());
        }

        Builder(Retrofit retrofit) {
            platform = Platform.get();
            callFactory = retrofit.callFactory;
            baseUrl = retrofit.baseUrl;

            // Do not add the default BuiltIntConverters and platform-aware converters added by build().
            for (int i = 1,
                 size = retrofit.converterFactories.size() - platform.defaultConverterFactoriesSize();
                 i < size; i++) {
                converterFactories.add(retrofit.converterFactories.get(i));
            }

            // Do not add the default, platform-aware call adapters added by build().
            for (int i = 0,
                 size = retrofit.callAdapterFactories.size() - platform.defaultCallAdapterFactoriesSize();
                 i < size; i++) {
                callAdapterFactories.add(retrofit.callAdapterFactories.get(i));
            }

            callbackExecutor = retrofit.callbackExecutor;
            validateEagerly = retrofit.validateEagerly;
        }

        /**
         * The HTTP client used for requests.
         * <p>
         * This is a convenience method for calling {@link #callFactory}.
         */
        public Builder client(OkHttpClient client) {
            return callFactory(Objects.requireNonNull(client, "client == null"));
        }

        /**
         * Specify a custom call factory for creating {@link Call} instances.
         * <p>
         * Note: Calling {@link #client} automatically sets this value.
         */
        public Builder callFactory(okhttp3.Call.Factory factory) {
            this.callFactory = Objects.requireNonNull(factory, "factory == null");
            return this;
        }

        /**
         * Set the API base URL.
         *
         * @see #baseUrl(HttpUrl)
         */
        public Builder baseUrl(URL baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl == null");
            return baseUrl(HttpUrl.get(baseUrl.toString()));
        }

        /**
         * Set the API base URL.
         *
         * @see #baseUrl(HttpUrl)
         */
        public Builder baseUrl(String baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl == null");
            return baseUrl(HttpUrl.get(baseUrl));
        }

        /**
         * Set the API base URL.
         * <p>
         * The specified endpoint values (such as with {@link GET @GET}) are resolved against this
         * value using {@link HttpUrl#resolve(String)}. The behavior of this matches that of an
         * {@code <a href="">} link on a website resolving on the current URL.
         * <p>
         * <b>Base URLs should always end in {@code /}.</b>
         * <p>
         * A trailing {@code /} ensures that endpoints values which are relative paths will correctly
         * append themselves to a base which has path components.
         * <p>
         * <b>Correct:</b><br>
         * Base URL: http://example.com/api/<br>
         * Endpoint: foo/bar/<br>
         * Result: http://example.com/api/foo/bar/
         * <p>
         * <b>Incorrect:</b><br>
         * Base URL: http://example.com/api<br>
         * Endpoint: foo/bar/<br>
         * Result: http://example.com/foo/bar/
         * <p>
         * This method enforces that {@code baseUrl} has a trailing {@code /}.
         * <p>
         * <b>Endpoint values which contain a leading {@code /} are absolute.</b>
         * <p>
         * Absolute values retain only the host from {@code baseUrl} and ignore any specified path
         * components.
         * <p>
         * Base URL: http://example.com/api/<br>
         * Endpoint: /foo/bar/<br>
         * Result: http://example.com/foo/bar/
         * <p>
         * Base URL: http://example.com/<br>
         * Endpoint: /foo/bar/<br>
         * Result: http://example.com/foo/bar/
         * <p>
         * <b>Endpoint values may be a full URL.</b>
         * <p>
         * Values which have a host replace the host of {@code baseUrl} and values also with a scheme
         * replace the scheme of {@code baseUrl}.
         * <p>
         * Base URL: http://example.com/<br>
         * Endpoint: https://github.com/square/retrofit/<br>
         * Result: https://github.com/square/retrofit/
         * <p>
         * Base URL: http://example.com<br>
         * Endpoint: //github.com/square/retrofit/<br>
         * Result: http://github.com/square/retrofit/ (note the scheme stays 'http')
         */
        public Builder baseUrl(HttpUrl baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl == null");
            List<String> pathSegments = baseUrl.pathSegments();
            if (!"".equals(pathSegments.get(pathSegments.size() - 1))) {
                throw new IllegalArgumentException("baseUrl must end in /: " + baseUrl);
            }
            this.baseUrl = baseUrl;
            return this;
        }

        /** Add converter factory for serialization and deserialization of objects. */
        public Builder addConverterFactory(Converter.Factory factory) {
            converterFactories.add(Objects.requireNonNull(factory, "factory == null"));
            return this;
        }

        /**
         * Add a call adapter factory for supporting service method return types other than {@link
         * Call}.
         */
        public Builder addCallAdapterFactory(CallAdapter.Factory factory) {
            callAdapterFactories.add(Objects.requireNonNull(factory, "factory == null"));
            return this;
        }

        /**
         * The executor on which {@link Callback} methods are invoked when returning {@link Call} from
         * your service method.
         * <p>
         * Note: {@code executor} is not used for {@linkplain #addCallAdapterFactory custom method
         * return types}.
         */
        public Builder callbackExecutor(Executor executor) {
            this.callbackExecutor = Objects.requireNonNull(executor, "executor == null");
            return this;
        }

        /** Returns a modifiable list of call adapter factories. */
        public List<CallAdapter.Factory> callAdapterFactories() {
            return this.callAdapterFactories;
        }

        /** Returns a modifiable list of converter factories. */
        public List<Converter.Factory> converterFactories() {
            return this.converterFactories;
        }

        /**
         * When calling {@link #create} on the resulting {@link Retrofit} instance, eagerly validate
         * the configuration of all methods in the supplied interface.
         */
        public Builder validateEagerly(boolean validateEagerly) {
            this.validateEagerly = validateEagerly;
            return this;
        }

        /**
         * Create the {@link Retrofit} instance using the configured values.
         * <p>
         * Note: If neither {@link #client} nor {@link #callFactory} is called a default {@link
         * OkHttpClient} will be created and used.
         */
        public Retrofit build() {
            if (baseUrl == null) {
                throw new IllegalStateException("Base URL required.");
            }

            okhttp3.Call.Factory callFactory = this.callFactory;
            if (callFactory == null) {
                callFactory = new OkHttpClient();
            }

            Executor callbackExecutor = this.callbackExecutor;
            if (callbackExecutor == null) {
                callbackExecutor = platform.defaultCallbackExecutor();
            }

            // Make a defensive copy of the adapters and add the default Call adapter.
            List<CallAdapter.Factory> callAdapterFactories = new ArrayList<>(this.callAdapterFactories);
            callAdapterFactories.addAll(platform.defaultCallAdapterFactories(callbackExecutor));

            // Make a defensive copy of the converters.
            List<Converter.Factory> converterFactories = new ArrayList<>(
                    1 + this.converterFactories.size() + platform.defaultConverterFactoriesSize());

            // Add the built-in converter factory first. This prevents overriding its behavior but also
            // ensures correct behavior when using converters that consume all types.
            converterFactories.add(new BuiltInConverters());
            converterFactories.addAll(this.converterFactories);
            converterFactories.addAll(platform.defaultConverterFactories());

            return new Retrofit(callFactory, baseUrl, unmodifiableList(converterFactories),
                    unmodifiableList(callAdapterFactories), callbackExecutor, validateEagerly);
        }
    }
}
