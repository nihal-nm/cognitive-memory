package io.github.rigazilla.memory.cognition.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/** Creates consistently configured, authenticated channels for memory-service. */
public final class GrpcChannelFactory {

    static final Metadata.Key<String> API_KEY_HEADER =
            Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> CLIENT_ID_HEADER =
            Metadata.Key.of("x-client-id", Metadata.ASCII_STRING_MARSHALLER);

    private GrpcChannelFactory() {
    }

    /** Creates a plaintext channel authenticated with an API key. */
    public static ManagedChannel create(String host, int port, String apiKey) {
        return create(host, port, apiKey, null);
    }

    /** Creates a plaintext channel authenticated with an API key and optional client ID. */
    public static ManagedChannel create(String host, int port, String apiKey, String clientId) {
        return ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .intercept(new AuthInterceptor(apiKey, clientId))
                .build();
    }

    static final class AuthInterceptor implements ClientInterceptor {
        private final String apiKey;
        private final String clientId;

        AuthInterceptor(String apiKey, String clientId) {
            this.apiKey = apiKey;
            this.clientId = clientId;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method,
                CallOptions callOptions,
                Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    headers.put(API_KEY_HEADER, apiKey);
                    if (clientId != null && !clientId.isBlank()) {
                        headers.put(CLIENT_ID_HEADER, clientId);
                    }
                    super.start(responseListener, headers);
                }
            };
        }
    }
}
